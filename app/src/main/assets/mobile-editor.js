(function(operation) {
    'use strict';
    if (!window.__dexcodeMobileInstalled) {
        window.__dexcodeMobileInstalled = true;
        window.__dexcodeMobileContext = 0;
        window.__dexcodeMobileComposing = false;
        let lastContext;
        document.addEventListener('compositionstart', e => {
            if (e.target?.closest('.monaco-workbench')) window.__dexcodeMobileComposing = true;
        }, true);
        document.addEventListener('compositionend', () => { window.__dexcodeMobileComposing = false; }, true);
        document.addEventListener('focusin', e => {
            const context = e.target?.closest('.monaco-editor') || e.target;
            if (context !== lastContext) { lastContext = context; window.__dexcodeMobileContext++; }
        }, true);
        document.addEventListener('pointerdown', e => {
            if (e.target?.closest('.monaco-workbench')) window.__dexcodeMobileContext++;
        }, true);
        window.addEventListener('blur', () => { window.__dexcodeMobileContext++; window.__dexcodeMobileComposing = false; });
    }
    if (operation === 'install') return 'ok';
    const workbench = document.querySelector('.monaco-workbench');
    if (!workbench) return 'not-ready';
    if (operation === 'context') return 'context:' + performance.timeOrigin + ':' + window.__dexcodeMobileContext;
    const active = document.activeElement;
    const terminal = e => !!e?.closest('.terminal, .xterm, .terminal-editor, .terminal-overflow-guard');
    const privateField = e => e?.type === 'password' || e?.tagName === 'IFRAME';
    const inside = e => !!e && workbench.contains(e) && !privateField(e) && !e.disabled;
    const editable = e => inside(e) && (e.tagName === 'TEXTAREA' ||
        (e.tagName === 'INPUT' && ['text','search','url','email','tel','number'].includes(e.type || 'text')) ||
        e.isContentEditable || e.classList.contains('native-edit-context'));
    const body = e => editable(e) && !!e.closest('.part.editor .monaco-editor') &&
        !e.closest('.find-widget, .rename-box, .suggest-widget') && !terminal(e);
    function codeInput() {
        const selector = '.monaco-editor textarea.inputarea, .monaco-editor .native-edit-context, .monaco-editor [contenteditable="true"]';
        const group = workbench.querySelector('.editor-group-container.active');
        const candidates = Array.from(group ? group.querySelectorAll(selector) :
            workbench.querySelectorAll('.part.editor ' + selector.split(', ').join(', .part.editor ')));
        return candidates.find(e => e.getClientRects().length && body(e) && !e.readOnly);
    }
    if (operation !== 'keyboard' && window.__dexcodeMobileComposing) return 'composing';
    if (operation === 'editor') return body(active) && !active.readOnly ? 'ok' : 'tap-editor';
    if (operation === 'paste') {
        if (!editable(active) || active.readOnly) return 'tap-editor';
        return terminal(active) ? 'terminal' : 'ok';
    }
    if (operation === 'text' || operation === 'read') {
        return editable(active) && !terminal(active) && (operation === 'read' || !active.readOnly) ? 'ok' : 'tap-editor';
    }
    if (operation === 'navigation') return inside(active) && !terminal(active) ? 'ok' : 'tap-editor';
    if (operation === 'save') {
        if (!inside(active) || terminal(active) || active.closest('.quick-input-widget, .monaco-dialog')) return 'tap-editor';
        if (body(active) && !active.readOnly) return 'ok';
        const target = codeInput();
        if (!target) return 'tap-editor';
        target.focus();
        return document.activeElement === target ? 'ok' : 'tap-editor';
    }
    if (operation !== 'keyboard' || privateField(active)) return 'tap-editor';
    // A terminal's own input may request IME, but never receives code-only shortcuts.
    if (editable(active) && !active.readOnly) return 'ok';
    const target = codeInput();
    if (!target) return 'tap-editor';
    target.focus();
    return document.activeElement === target ? 'ok' : 'tap-editor';
})
