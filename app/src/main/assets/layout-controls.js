(function(action, stage, id) {
    'use strict';
    const selectors = {sidebar: '.part.sidebar', panel: '.part.panel', auxiliary: '.part.auxiliarybar'};
    const workbench = document.querySelector('.monaco-workbench');
    if (!workbench || !selectors[action]) return 'unavailable';
    const visible = element => !!element && element.getClientRects().length > 0 &&
        getComputedStyle(element).display !== 'none' && getComputedStyle(element).visibility !== 'hidden';
    const state = () => visible(workbench.querySelector(selectors[action]));
    if (stage === 0) {
        window.__dexcodeLayoutToggle = {action, before: state()};
        // VS Code 1.136 uses registered icon aliases instead of the older layout-* names.
        // Resolve the live control, including for fixed buttons without a snapshot ID.
        const icons = {
            sidebar: ['panel-left', 'panel-right', 'layout-sidebar-left'],
            panel: ['panel-layout-icon', 'layout-panel'],
            auxiliary: ['auxiliarybar-left-layout-icon', 'auxiliarybar-right-layout-icon',
                'auxiliarybar-left-off-layout-icon', 'auxiliarybar-right-off-layout-icon', 'layout-sidebar-right']
        };
        const selector = icons[action].flatMap(icon => [icon, icon + '-off'])
            .map(icon => '.part.titlebar .codicon-' + icon).join(',');
        const button = (Number(id) > 0 && workbench.querySelector('.part.titlebar [data-dexcode-control="' + Number(id) + '"]')) ||
            Array.from(workbench.querySelectorAll(selector)).find(visible);
        if (visible(button) && button.getAttribute('aria-disabled') !== 'true' && !button.disabled) {
            // HTMLElement.click() omits the window context read by VS Code's mouse-event wrapper.
            button.dispatchEvent(new MouseEvent('click', {view: window, button: 0, detail: 1,
                bubbles: true, cancelable: true, composed: true}));
        }
        return 'sent';
    }
    const pending = window.__dexcodeLayoutToggle;
    if (!pending || pending.action !== action) return 'unavailable';
    if (state() !== pending.before) return 'ok';
    if (stage !== 1) return 'unavailable';
    // The cropped titlebar may have been rebuilt or its action may ignore a DOM click.
    // Use VS Code's own keybinding service only after confirming no toggle occurred.
    const key = action === 'panel' ? 'j' : 'b';
    const keyCode = key.toUpperCase().charCodeAt(0);
    const target = workbench.contains(document.activeElement) ? document.activeElement : workbench;
    for (const type of ['keydown', 'keyup']) {
        const event = new KeyboardEvent(type, {key, code: 'Key' + key.toUpperCase(), keyCode,
            which: keyCode, ctrlKey: true, altKey: action === 'auxiliary',
            bubbles: true, cancelable: true, composed: true});
        for (const property of ['keyCode', 'which']) {
            if (event[property] !== keyCode) Object.defineProperty(event, property, {get: () => keyCode});
        }
        target.dispatchEvent(event);
    }
    return 'sent';
})
