(() => {
    if (window !== window.top) return;
    const host = { frameLoads: 0, frameErrors: 0, bootstrap: 0, portReady: 0, frameReportedError: 0,
        originCheck: 0, moduleFetch: 0, policy: 0 };
    function isExtensionFrame(frame) {
        if (!(frame instanceof HTMLIFrameElement)) return false;
        try {
            const url = new URL(frame.src);
            return url.protocol === 'https:' && url.pathname.endsWith('/webWorkerExtensionHostIframe.html') &&
                (url.origin === location.origin || url.hostname.endsWith('.vscode-cdn.net'));
        } catch (_) { return false; }
    }
    document.addEventListener('load', event => {
        if (isExtensionFrame(event.target)) host.frameLoads++;
    }, true);
    document.addEventListener('error', event => {
        if (isExtensionFrame(event.target)) host.frameErrors++;
    }, true);
    window.addEventListener('message', event => {
        const frame = Array.from(document.querySelectorAll('iframe')).find(candidate =>
            isExtensionFrame(candidate) && candidate.contentWindow === event.source &&
            new URL(candidate.src).origin === event.origin);
        const data = event.data;
        if (!frame || !data || typeof data.vscodeWebWorkerExtHostId !== 'string') return;
        if (data.type === 'vscode.bootstrap.nls') host.bootstrap++;
        if (event.ports.length) host.portReady++;
        if (data.error) {
            host.frameReportedError++;
            const message = typeof data.error.message === 'string' ? data.error.message.toLowerCase() : '';
            if (/parentorigin|as subdomain|cannot validate/.test(message)) host.originCheck++;
            if (/failed to fetch|importing a module|module script|dynamically imported/.test(message)) host.moduleFetch++;
            if (/securityerror|content security policy|cross.origin|blocked/.test(message)) host.policy++;
        }
    });
    Object.defineProperty(window, '__dexcodeExtensionHostStartup', {
        value: () => ({ ...host }), configurable: false, writable: false
    });
    if (typeof window.WebSocket !== 'function') return;
    const counts = { attempted: 0, opened: 0, errors: 0, closed: 0, abnormal: 0, lastCloseCode: 0 };
    window.WebSocket = new Proxy(window.WebSocket, {
        construct(target, args, newTarget) {
            const socket = Reflect.construct(target, args, newTarget);
            counts.attempted++;
            socket.addEventListener('open', () => { counts.opened++; }, { once: true });
            socket.addEventListener('error', () => { counts.errors++; }, { once: true });
            socket.addEventListener('close', event => {
                counts.closed++;
                if (!event.wasClean) counts.abnormal++;
                counts.lastCloseCode = event.code;
            }, { once: true });
            return socket;
        }
    });
    Object.defineProperty(window, '__dexcodeTunnelTransport', {
        value: () => ({ ...counts }), configurable: false, writable: false
    });
})();
