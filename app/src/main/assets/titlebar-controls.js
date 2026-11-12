(function(operation, id) {
    'use strict';
    const bar = document.querySelector('.monaco-workbench .part.titlebar');
    const visible = e => !!e && e.getClientRects().length > 0 && getComputedStyle(e).visibility !== 'hidden';
    const ready = visible(document.querySelector('.monaco-workbench'));
    if (!visible(bar)) return JSON.stringify({height: 0, width: innerWidth, controls: [], ready});
    if (operation === 'click') {
        const button = bar.querySelector('[data-dexcode-control="' + Number(id) + '"]');
        if (!button || !visible(button) || button.getAttribute('aria-disabled') === 'true' || button.disabled) return 'unavailable';
        button.click();
        return 'sent';
    }
    const rect = bar.getBoundingClientRect();
    const candidates = Array.from(bar.querySelectorAll('button, a.action-label, [role="button"], .menubar-menu-button, [tabindex="0"]'))
        .filter(e => visible(e) && !e.closest('.command-center-quick-pick') && !(e.closest('.command-center') && e.classList.contains('codicon-search')));
    // A nested label and its button are one control, not two native buttons.
    const buttons = candidates.filter(e => !candidates.some(child => child !== e && e.contains(child)));
    let next = Number(bar.dataset.dexcodeNext || 0);
    const controls = buttons.map(e => {
        if (!e.dataset.dexcodeControl) e.dataset.dexcodeControl = String(++next);
        const childIcon = typeof e.querySelector === 'function' ? e.querySelector('.codicon') : null;
        const ownIcon = Array.from(e.classList).find(c => c.startsWith('codicon-') && !c.startsWith('codicon-modifier-'));
        const icon = ownIcon || (childIcon && Array.from(childIcon.classList).find(c => c.startsWith('codicon-') && !c.startsWith('codicon-modifier-'))) || '';
        const label = e.getAttribute('aria-label') || e.getAttribute('title') || e.textContent.trim() || icon || 'VS Code';
        return {id: Number(e.dataset.dexcodeControl), label, icon,
            enabled: e.getAttribute('aria-disabled') !== 'true' && !e.disabled};
    });
    bar.dataset.dexcodeNext = String(next);
    // Only a topmost, measured titlebar can be cropped. Editor tabs and all other parts stay untouched.
    const safe = Math.abs(rect.top) <= 1 && rect.height > 0 && rect.height <= 100 && innerWidth > 0;
    const crop = Number(id) === 1 && safe ? rect.bottom : 0;
    // Recent workbenches place Quick Input at y=6, inside the titlebar cropped by Android.
    // Keep only popups overlapping that crop below it; centered/otherwise positioned inputs stay put.
    window.__dexcodePositionQuickInput = () => {
        if (crop <= 0) return;
        for (const popup of document.querySelectorAll('.monaco-workbench .quick-input-widget')) {
            // Switching providers, typing and resizing can reset VS Code's inline top value.
            // Observe only the popup's own style, avoiding a workbench-wide mutation listener.
            if (typeof MutationObserver === 'function') {
                const watched = window.__dexcodeQuickInputWatched || (window.__dexcodeQuickInputWatched = new WeakSet());
                if (!watched.has(popup)) {
                    new MutationObserver(() => window.__dexcodePositionQuickInput()).observe(popup, {attributes: true, attributeFilter: ['style']});
                    watched.add(popup);
                }
            }
            if (visible(popup) && popup.getBoundingClientRect().top < crop) popup.style.top = (crop + 6) + 'px';
        }
    };
    window.__dexcodePositionQuickInput();
    return JSON.stringify({height: safe ? rect.bottom : 0, width: innerWidth, controls, ready});
})
