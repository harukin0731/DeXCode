(function() {
  'use strict';
  const bridge = window.DeXCodeDownload;
  if (!bridge || window.__dexcodeDownload) return;
  let sequence = 0, queued = 0; const pending = new Map(); let tail = Promise.resolve();
  const blobs = new Map();
  const objectURL = window.URL;
  if (objectURL && objectURL.createObjectURL) {
    const create = objectURL.createObjectURL.bind(objectURL), revoke = objectURL.revokeObjectURL.bind(objectURL);
    objectURL.createObjectURL = function(blob) {
      const url = create(blob);
      // Weak references do not keep unrelated image/media blobs alive.
      blobs.set(url, typeof WeakRef === 'function' ? new WeakRef(blob) : blob);
      return url;
    };
    objectURL.revokeObjectURL = function(url) { blobs.delete(String(url)); return revoke(url); };
  }
  bridge.onmessage = event => {
    try {
      const response = JSON.parse(event.data), item = pending.get(response.id);
      if (!item) return;
      pending.delete(response.id); clearTimeout(item.timer);
      if (response.ok) item.resolve();
      else { const error = new Error(response.error || 'native_rejected'); error.nativeType = response.exception || ''; item.reject(error); }
    } catch (_) {}
  };
  function send(value) {
    return new Promise((resolve,reject) => {
      const id = ++sequence;
      const timer = setTimeout(() => {pending.delete(id); reject(new Error('bridge_timeout'));},30000);
      pending.set(id,{resolve,reject,timer});
      try {bridge.postMessage(JSON.stringify({...value,id}));}
      catch (_) {clearTimeout(timer); pending.delete(id); reject(new Error('bridge_send_failed'));}
    });
  }
  function rememberedBlob(url) {
    const value = blobs.get(url);
    return value && typeof value.deref === 'function' ? value.deref() : value;
  }
  async function bytesOf(blob) {
    if (typeof blob.arrayBuffer === 'function') return blob.arrayBuffer();
    if (typeof FileReader === 'undefined') throw new Error('blob_reader_unavailable');
    return new Promise((resolve,reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result);
      reader.onerror = () => reject(new Error('blob_read_failed'));
      reader.readAsArrayBuffer(blob);
    });
  }
  async function transferFile(url, filename, mime, captured) {
    const transfer = String(Date.now()) + '-' + String(Math.random());
    let phase = 'source';
    try {
      if (!/^(blob:|data:)/i.test(url)) throw new Error('unsupported_scheme');
      let blob = captured;
      if (!blob) {
        try {
          const response = await fetch(url);
          if (!response.ok) throw new Error();
          blob = await response.blob();
        } catch (_) { throw new Error('temporary_url_unavailable'); }
      }
      if (blob.size > 134217728) throw new Error('file_too_large');
      phase = 'begin';
      await send({op:'begin',transfer,filename:filename || 'download',mime:mime || blob.type,size:blob.size});
      phase = 'chunk';
      for (let offset = 0; offset < blob.size; offset += 49152) {
        const bytes = new Uint8Array(await bytesOf(blob.slice(offset,offset+49152)));
        let binary = ''; for (const byte of bytes) binary += String.fromCharCode(byte);
        await send({op:'chunk',transfer,data:btoa(binary)});
      }
      phase = 'end';
      await send({op:'end',transfer});
    } catch (error) {
      const reason = /^[a-z_]{1,48}$/.test(error.message || '') ? error.message : 'browser_read_failed';
      const nativeType = /^[A-Za-z_]{1,48}$/.test(error.nativeType || '') ? error.nativeType : '';
      await send({op:'abort',transfer,reason,phase,nativeType}).catch(() => {});
    }
  }
  window.__dexcodeDownload = function(url, filename, mime) {
    // Capture the Blob synchronously, before VS Code revokes its URL on the next task.
    const blob = rememberedBlob(url);
    if (queued >= 16) return send({op:'abort',transfer:'queue-full',reason:'queue_full',phase:'source'}).catch(() => {});
    queued++;
    const job = tail.then(() => transferFile(url,filename,mime,blob));
    tail = job.catch(() => {});
    return job.finally(() => {queued--;});
  };
  function intercept(anchor) {
    if (!anchor || !/^(blob:|data:)/i.test(anchor.href)) return false;
    window.__dexcodeDownload(anchor.href,anchor.download,'');
    return true;
  }
  // Covers synthetic clicks, including anchors not attached to document, without launching
  // the WebView download listener a second time. HTTP links keep their normal behavior.
  if (typeof HTMLAnchorElement !== 'undefined') {
    const click = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function() {
      if (this.hasAttribute('download') && intercept(this)) return;
      return click.call(this);
    };
  }
  document.addEventListener('click', event => {
    const anchor = event.target && event.target.closest && event.target.closest('a[download]');
    if (intercept(anchor)) event.preventDefault();
  }, true);
})();
