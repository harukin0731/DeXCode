(function(operation, id) {
    'use strict';
    const bar = document.querySelector('.monaco-workbench .part.titlebar');
    const visible = e => !!e && e.getClientRects().length > 0 && getComputedStyle(e).visibility !== 'hidden';
    if (!visible(bar)) return JSON.stringify({height: 0, width: innerWidth, controls: []});
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
        const icon = Array.from(e.classList).find(c => c.startsWith('codicon-') && c !== 'codicon') || '';
        const label = e.getAttribute('aria-label') || e.getAttribute('title') || e.textContent.trim() || icon || 'VS Code';
        return {id: Number(e.dataset.dexcodeControl), label, icon,
            enabled: e.getAttribute('aria-disabled') !== 'true' && !e.disabled};
    });
    bar.dataset.dexcodeNext = String(next);
    // Only a topmost, measured titlebar can be cropped. Editor tabs and all other parts stay untouched.
    const safe = Math.abs(rect.top) <= 1 && rect.height > 0 && rect.height <= 100 && innerWidth > 0;
    return JSON.stringify({height: safe ? rect.bottom : 0, width: innerWidth, controls});
})
