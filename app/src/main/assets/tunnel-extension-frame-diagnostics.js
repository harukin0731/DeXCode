(() => {
    if (window === window.top ||
        !location.pathname.endsWith('/webWorkerExtensionHostIframe.html')) return;

    const note = (stage) => console.warn('DEXCODE_EXT_FRAME ' + stage);
    note('document-start');
    if (window.isSecureContext && window.crypto?.subtle) note('secure-crypto');
    window.addEventListener('securitypolicyviolation', event => {
        const known = ['script-src', 'script-src-elem', 'worker-src', 'child-src', 'connect-src', 'default-src'];
        note(known.includes(event.effectiveDirective) ? 'csp-' + event.effectiveDirective : 'csp-other');
    });
    window.addEventListener('error', () => note('script-error'));
    window.addEventListener('unhandledrejection', () => note('script-rejection'));
    window.addEventListener('DOMContentLoaded', () => {
        note(document.querySelector('script') ? 'document-has-script' : 'document-no-script');
    }, { once: true });
    window.addEventListener('message', event => {
        if (event.source === window.parent &&
            ['https://vscode.dev', 'https://insiders.vscode.dev'].includes(event.origin) &&
            event.data?.type === 'vscode.bootstrap.nls') note('parent-bootstrap');
    });
    if (typeof Worker === 'function') {
        window.Worker = new Proxy(Worker, {
            construct(target, args, newTarget) {
                note('worker-constructor');
                try {
                    const worker = Reflect.construct(target, args, newTarget);
                    worker.addEventListener('error', () => note('worker-error'), { once: true });
                    worker.addEventListener('message', () => note('worker-message'), { once: true });
                    return worker;
                } catch (error) {
                    note('worker-constructor-error');
                    throw error;
                }
            }
        });
    }
})();
