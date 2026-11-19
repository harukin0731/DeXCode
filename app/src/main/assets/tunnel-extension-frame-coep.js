(() => {
    if (window !== window.top || !['https://vscode.dev', 'https://insiders.vscode.dev'].includes(location.origin)) return;
    function configure(frame) {
        if (!(frame instanceof HTMLIFrameElement)) return;
        let url;
        try { url = new URL(frame.src); } catch (_) { return; }
        if (url.protocol !== 'https:' || url.port || url.username || url.password ||
            !/^v--[0-9a-v]{52}\.vscode-cdn\.net$/.test(url.hostname) ||
            !/^\/(stable|insider)\/[a-f0-9]{40}\/out\/vs\/workbench\/services\/extensions\/worker\/webWorkerExtensionHostIframe\.html$/.test(url.pathname) ||
            url.searchParams.get('parentOrigin') !== location.origin ||
            !url.searchParams.get('vscodeWebWorkerExtHostId')) return;
        const mode = url.searchParams.get('vscode-coi');
        if (mode === '2' || mode === '3') return;
        url.searchParams.set('vscode-coi', mode === '1' ? '3' : '2');
        frame.src = url.href;
    }
    new MutationObserver(records => {
        for (const record of records) {
            if (record.type === 'attributes') {
                configure(record.target);
                continue;
            }
            for (const node of record.addedNodes) {
                if (!(node instanceof Element)) continue;
                configure(node);
                node.querySelectorAll('iframe').forEach(configure);
            }
        }
    }).observe(document, { subtree: true, childList: true, attributes: true, attributeFilter: ['src'] });
})();
