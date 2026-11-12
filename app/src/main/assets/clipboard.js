(function() {
  'use strict';
  const bridge = window.DeXCodeClipboard;
  if (!bridge || typeof bridge.postMessage !== 'function' || window.__dexcodeClipboardInstalled) return;
  window.__dexcodeClipboardInstalled = true;
  const status = {gestures:0, attempts:0, extracted:0, writes:0, characters:0, region:'none', outcome:'waiting', method:'none', fallbackRequests:0};
  const pending = new Map(); let sequence = 0;
  window.__dexcodeClipboardDiagnostics = () => ({...status,
    terminals:document.querySelectorAll('.xterm').length,
    editorTerminals:document.querySelectorAll('.terminal-editor .xterm').length});
  bridge.onmessage = event => {
    try {
      const reply = JSON.parse(event.data), entry = pending.get(reply.id);
      if (!entry) return;
      pending.delete(reply.id); clearTimeout(entry.timer);
      if (reply.ok) entry.resolve(reply.text || '');
      else entry.reject(new DOMException('Clipboard access denied', 'NotAllowedError'));
    } catch (_) {}
  };
  function request(op, text) {
    return new Promise((resolve, reject) => {
      const id = ++sequence;
      const timer = setTimeout(() => { pending.delete(id); reject(new Error('Clipboard request timed out')); }, 5000);
      pending.set(id, {resolve, reject, timer});
      try { bridge.postMessage(JSON.stringify({id, op, text})); }
      catch (error) { clearTimeout(timer); pending.delete(id); reject(error); }
    });
  }
  const clipboard = navigator.clipboard || {};
  const original = {};
  for (const name of ['readText', 'writeText', 'read', 'write']) {
    if (typeof clipboard[name] === 'function') original[name] = clipboard[name].bind(clipboard);
  }
  const fallback = (name, args, error) => original[name] ? original[name](...args) : Promise.reject(error);
  const relayPending = new Map(); let relaySequence = 0;
  function relayWrite(text, automatic = false) {
    if (window.parent === window) return Promise.reject(new DOMException('No clipboard parent', 'NotAllowedError'));
    return new Promise((resolve, reject) => {
      const id = ++relaySequence;
      const timer = setTimeout(() => { relayPending.delete(id); reject(new Error('Copy relay timed out')); }, 5000);
      relayPending.set(id, {resolve, reject, timer});
      window.parent.postMessage({kind:'dexcode-copy-write', id, text, automatic}, '*');
    });
  }
  window.addEventListener('message', event => {
    const data = event.data;
    if (!data || typeof data !== 'object') return;
    if (data.kind === 'dexcode-copy-reply' && event.source === window.parent) {
      const pending = relayPending.get(data.id);
      if (!pending) return;
      relayPending.delete(data.id); clearTimeout(pending.timer);
      data.ok ? pending.resolve() : pending.reject(new DOMException('Copy relay denied', 'NotAllowedError'));
      return;
    }
    if (data.kind !== 'dexcode-copy-write' || typeof data.text !== 'string' || data.text.length > 262144) return;
    // Only child frames embedded by the workbench can relay a text write. Never relay clipboard reads.
    const frames = document.querySelectorAll(window.parent === window ? '.monaco-workbench iframe' : 'iframe');
    if (!Array.from(frames).some(frame => frame.contentWindow === event.source)) return;
    (data.automatic === true ? selectedText(data.text) : writeText(data.text)).then(() => true, () => false).then(ok => {
      event.source.postMessage({kind:'dexcode-copy-reply', id:data.id, ok}, '*');
    });
  });
  const writeText = value => request('write', String(value)).then(() => undefined)
    .catch(error => relayWrite(String(value)).catch(() => fallback('writeText', [value], error)));
  const selectedText = text => request('select', text).then(() => undefined).catch(() => relayWrite(text, true));
  const readText = () => request('read').catch(error => fallback('readText', [], error));
  const methods = {writeText, readText,
    read: () => original.read ? original.read().catch(() => readText().then(text => [new ClipboardItem({'text/plain': new Blob([text], {type:'text/plain'})})]))
      : readText().then(text => [new ClipboardItem({'text/plain': new Blob([text], {type:'text/plain'})})]),
    write: async items => {
      const values = await items;
      const item = values && values.find(value => Array.from(value.types || []).includes('text/plain'));
      if (!item) return fallback('write', [values], new DOMException('Only text is supported', 'NotSupportedError'));
      const blob = await item.getType('text/plain');
      return writeText(await blob.text());
    }
  };
  for (const [name, value] of Object.entries(methods)) {
    try { Object.defineProperty(clipboard, name, {value, configurable:true}); } catch (_) {}
  }
  if (!navigator.clipboard) {
    try { Object.defineProperty(navigator, 'clipboard', {value:clipboard, configurable:true}); } catch (_) {}
  }
  function selection() {
    const active = document.activeElement;
    if (active && active.type === 'password') return '';
    if (active && typeof active.selectionStart === 'number')
      return active.value.substring(active.selectionStart, active.selectionEnd);
    return String(window.getSelection() || '');
  }
  // Legacy copy buttons (including terminal and extension UIs) still use execCommand/copy events.
  let copyEventSeen = false, probingSelection = false;
  document.addEventListener('copy', event => {
    if (probingSelection) return;
    copyEventSeen = true;
    const selected = selection();
    queueMicrotask(() => {
      const text = event.clipboardData && event.clipboardData.getData('text/plain') || selected;
      if (text) writeText(text).catch(() => {});
    });
  });
  let dragging = false, gestureTime = 0, selectionTarget = null, selectionTimer = null;
  let lastSelected = '', lastSelectedAt = 0;
  function eligible(target) {
    if (!target || typeof target.closest !== 'function' || target.closest('input[type="password"]')) return false;
    if (target.closest('.xterm')) return true;
    // Do not auto-copy ordinary source-editor selections or input composition.
    if (target.closest('.monaco-editor')) return false;
    return window.parent !== window || !!target.closest('.chat-widget, .interactive-session, .interactive-item-container');
  }
  function gestureTarget(event) {
    const target = event.target;
    if (!target || typeof target.closest !== 'function') return target;
    if (target.closest('input[type="password"]')) return target;
    const path = typeof event.composedPath === 'function' ? event.composedPath() : [];
    const terminal = target.closest('.xterm') || path.find(node => node && typeof node.matches === 'function' && node.matches('.xterm'));
    if (terminal) return terminal;
    if (target.closest('.monaco-editor')) return target;
    return target.closest('.chat-widget, .interactive-session, .interactive-item-container') || target;
  }
  function terminalText(terminal) {
    let text = '';
    status.method = 'copy_event';
    try {
      const data = new DataTransfer();
      probingSelection = true;
      const input = typeof terminal.querySelector === 'function' ? terminal.querySelector('.xterm-helper-textarea') : null;
      (input || terminal).dispatchEvent(new ClipboardEvent('copy', {clipboardData:data, bubbles:true, cancelable:true}));
      text = data.getData('text/plain');
    } catch (error) { status.outcome = 'terminal_probe_failed'; }
    finally { probingSelection = false; }
    return text;
  }
  async function commitSelection(text, automatic) {
    if (!text) { status.outcome = 'no_selection'; return false; }
    if (text.length > 262144) { status.outcome = 'selection_too_large'; return false; }
    if (automatic && text === lastSelected && Date.now() - lastSelectedAt < 1000) { status.outcome = 'written'; return true; }
    status.extracted++; status.characters = text.length; status.outcome = 'sending';
    try {
      await (automatic ? selectedText(text) : writeText(text));
      lastSelected = text; lastSelectedAt = Date.now();
      status.writes++; status.outcome = 'written'; return true;
    } catch (_) { status.outcome = 'native_write_rejected'; return false; }
  }
  function recordRegion(terminal) {
    status.region = terminal.closest('.terminal-editor') ? 'editor-terminal' : 'terminal';
  }
  function copySelection() {
    if (dragging || Date.now() - gestureTime > 3000 || !eligible(selectionTarget) || selectionTarget.isConnected === false) return;
    status.attempts++;
    const terminal = selectionTarget.closest('.xterm');
    let text = '';
    if (terminal) {
      recordRegion(terminal);
      text = terminalText(terminal);
      if (!text) text = String(window.getSelection() || '');
    } else { status.region = 'agent-text'; text = selection(); }
    if (!text) {
      // Never inject copy shortcuts: xterm can forward Ctrl+Shift+C as Ctrl+C
      // when the workbench does not consume it, interrupting the running agent.
      status.outcome = 'no_selection';
      return;
    }
    if (text === lastSelected && Date.now() - lastSelectedAt < 1000) return;
    commitSelection(text, true).catch(() => {});
  }
  // Explicit native menu action works independently of automatic-copy preference and gesture capture.
  window.__dexcodeCopyTerminalSelection = async function() {
    status.attempts++;
    const roots = Array.from(document.querySelectorAll('.xterm'));
    const active = document.activeElement && document.activeElement.closest ? document.activeElement.closest('.xterm') : null;
    const previous = selectionTarget && selectionTarget.closest ? selectionTarget.closest('.xterm') : null;
    const candidates = Array.from(new Set([active,previous,...roots])).filter(root => root && root.isConnected !== false && root.getClientRects().length);
    for (const root of candidates) {
      const text = terminalText(root);
      if (!text) continue;
      recordRegion(root);
      return await commitSelection(text, false) ? 'written' : status.outcome;
    }
    if (candidates.length) {
      recordRegion(candidates[0]);
      status.outcome = 'no_selection';
      return status.outcome;
    }
    status.outcome = roots.length ? 'no_selection' : 'terminal_not_found';
    return status.outcome;
  };
  function scheduleSelection() {
    clearTimeout(selectionTimer);
    selectionTimer = setTimeout(copySelection, 120);
  }
  let terminalSelectionMode = false, forcedGesture = false, selectionModeFromHost = false;
  window.__dexcodeSetTerminalSelectionMode = (value, fromHost = true) => {
    if (fromHost) selectionModeFromHost = true;
    else if (selectionModeFromHost) return;
    const enabled = value === true;
    if (terminalSelectionMode !== enabled) {
      terminalSelectionMode = enabled; forcedGesture = false;
      status.selectionMode = enabled;
    }
  };
  status.selectionMode = false;
  // A persisted preference is also available to same-origin child frames.
  setTimeout(() => request('selection_mode').then(value => window.__dexcodeSetTerminalSelectionMode(value === 'enabled', false)).catch(() => {}), 300);
  function forceTerminalSelection(event, beginning) {
    if (event.isTrusted !== true) return;
    if (beginning) {
      const target = gestureTarget(event);
      forcedGesture = terminalSelectionMode && !!(target && target.closest && target.closest('.xterm'));
    }
    if (!forcedGesture) return;
    try {
      Object.defineProperty(event, 'shiftKey', {value:true,configurable:true});
      const modifiers = typeof event.getModifierState === 'function' ? event.getModifierState.bind(event) : () => false;
      Object.defineProperty(event, 'getModifierState', {value:name => name === 'Shift' || modifiers(name),configurable:true});
    } catch (_) { status.outcome = 'selection_modifier_failed'; }
  }
  const seenGestures = new WeakSet();
  function trusted(event) {
    if (event.isTrusted !== true || seenGestures.has(event)) return false;
    seenGestures.add(event); return true;
  }
  function beginGesture(event) {
    if (!trusted(event)) return;
    forceTerminalSelection(event, true);
    status.gestures++; status.outcome = 'selecting';
    dragging = true; gestureTime = Date.now(); selectionTarget = gestureTarget(event);
  }
  function endGesture(event) {
    if (!trusted(event)) return;
    forceTerminalSelection(event, false);
    dragging = false; gestureTime = Date.now();
    const released = gestureTarget(event);
    if (eligible(released)) selectionTarget = released;
    scheduleSelection();
  }
  // Capture at window as well as document: workbench/editor handlers can consume release events.
  for (const target of [window, document]) {
    target.addEventListener('pointerdown', beginGesture, true);
    target.addEventListener('pointerup', endGesture, true);
    target.addEventListener('mousedown', beginGesture, true);
    target.addEventListener('mouseup', event => { endGesture(event); forcedGesture = false; }, true);
    target.addEventListener('mousemove', event => forceTerminalSelection(event, false), true);
    target.addEventListener('pointermove', event => forceTerminalSelection(event, false), true);
  }
  document.addEventListener('pointercancel', () => { dragging = false; clearTimeout(selectionTimer); }, true);
  document.addEventListener('keyup', event => {
    if (event.isTrusted !== true || !(event.shiftKey || ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'a'))) return;
    status.gestures++; selectionTarget = gestureTarget(event); gestureTime = Date.now(); scheduleSelection();
  }, true);
  document.addEventListener('selectionchange', () => {
    if (!dragging && Date.now() - gestureTime < 3000) scheduleSelection();
  });
  const exec = document.execCommand && document.execCommand.bind(document);
  if (exec) document.execCommand = function(command, ...args) {
    if (String(command).toLowerCase() !== 'copy') return exec(command, ...args);
    copyEventSeen = false;
    let result = false;
    try { result = exec(command, ...args); } catch (_) {}
    if (!copyEventSeen) {
      const text = selection();
      if (text) { writeText(text).catch(() => {}); return true; }
    }
    return result;
  };
})();
