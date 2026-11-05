(function(action, stage) {
    'use strict';
    const workbench = document.querySelector('.monaco-workbench');
    const visible = element => !!element && element.getClientRects().length > 0 &&
        getComputedStyle(element).visibility !== 'hidden' && getComputedStyle(element).display !== 'none';
    if (!visible(workbench)) return 'not-ready';
    const quickInput = () => Array.from(workbench.querySelectorAll('.quick-input-widget input')).find(visible);
    const terminalVisible = () => Array.from(workbench.querySelectorAll('.part.panel .terminal')).some(visible);
    function key(key, code, keyCode, ctrl, shift) {
        const target = workbench.contains(document.activeElement) ? document.activeElement : workbench;
        for (const type of ['keydown', 'keyup']) {
            const event = new KeyboardEvent(type, { key, code, keyCode, which: keyCode,
                ctrlKey: ctrl, shiftKey: shift, bubbles: true, cancelable: true, composed: true });
            // VS Code's StandardKeyboardEvent still reads these legacy numeric fields.
            for (const property of ['keyCode', 'which']) {
                if (event[property] !== keyCode) Object.defineProperty(event, property, { get: () => keyCode });
            }
            target.dispatchEvent(event);
        }
    }
    if (action === 'terminal') {
        if (stage === 0) {
            window.__dexcodeTerminalBefore = terminalVisible();
            key('`', 'Backquote', 192, true, false);
            return 'sent';
        }
        return terminalVisible() !== window.__dexcodeTerminalBefore ? 'ok' : 'unavailable';
    }
    if (stage === 2) {
        const input = quickInput();
        if (!input) return 'unavailable';
        const value = action === 'commands' ? '>' : '';
        if (input.value !== value) {
            Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(input, value);
            input.dispatchEvent(new Event('input', { bubbles: true }));
        }
        input.focus();
        return input.value === value ? 'ok' : 'unavailable';
    }
    if (stage === 0) {
        if (quickInput()) return 'sent';
        // Invoke the workbench's own command-center action rather than Android's Ctrl+P path.
        const button = Array.from(workbench.querySelectorAll(
            '.command-center .command-center-quick-pick, .command-center .action-label.codicon-search'
        )).find(visible);
        if (button) { button.click(); return 'sent'; }
    }
    if (action === 'commands') key('F1', 'F1', 112, false, false);
    else key('p', 'KeyP', 80, true, false);
    return 'sent';
})
