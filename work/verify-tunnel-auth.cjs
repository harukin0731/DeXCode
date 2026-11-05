// Run with Node.js from the project root. Exercises the actual Kotlin JS templates.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('app/src/main/java/com/example/dexcode/TunnelAuthScripts.kt', 'utf8');
function script(name, values) {
  const template = source.split(`fun ${name}(`)[1].split('"""')[1];
  return template.replace(/\$\{JSONObject\.quote\((\w+)\)\}/g, (_, key) => JSON.stringify(values[key]));
}
function page(url) {
  const entries = new Map();
  const events = [];
  const localStorage = {
    getItem: key => entries.get(key) ?? null,
    setItem: (key, value) => entries.set(key, value),
  };
  return {
    entries, events,
    context: {
      URL,
      window: { location: new URL(url), localStorage, dispatchEvent: event => events.push(event) },
      StorageEvent: class { constructor(type, data) { this.type = type; Object.assign(this, data); } },
    },
  };
}
const requestId = 'request-84d08123-85c4-4e70';
const key = `vscode-dev.url-callbacks[${requestId}]`;
const value = 'opaque+ciphertext/with="quotes"\\and\nnewlines';
const read = script('read', { requestId });
const child = page(`https://vscode.dev/callback?vscode-reqid=${requestId}`);
assert.equal(vm.runInNewContext(read, child.context), '');
child.entries.set(key, value); // Delayed write after the first read.
assert.deepEqual(JSON.parse(vm.runInNewContext(read, child.context)), [key, value]);
for (const url of [
  `http://vscode.dev/callback?vscode-reqid=${requestId}`,
  `https://vscode.dev.evil.example/callback?vscode-reqid=${requestId}`,
  `https://vscode.dev/tunnel/host?vscode-reqid=${requestId}`,
  'https://vscode.dev/callback?vscode-reqid=another-request',
]) {
  child.context.window.location = new URL(url);
  assert.equal(vm.runInNewContext(read, child.context), '');
}
const insider = page(`https://insiders.vscode.dev/callback?vscode-reqid=${requestId}`);
const alternateKey = `vscode-web.url-callbacks[${requestId}]`;
insider.entries.set(alternateKey, value);
assert.deepEqual(JSON.parse(vm.runInNewContext(read, insider.context)), [alternateKey, value]);
const parent = page('https://vscode.dev/tunnel/host/workspace');
const deliver = script('deliver', { origin: 'https://vscode.dev', key, value });
parent.entries.set(key, 'previous');
assert.equal(vm.runInNewContext(deliver, parent.context), true);
assert.equal(parent.entries.get(key), value);
assert.equal(parent.events.length, 1);
assert.equal(parent.events[0].oldValue, 'previous');
assert.equal(parent.events[0].newValue, value);
assert.equal(parent.events[0].storageArea, parent.context.window.localStorage);
assert.equal(parent.events[0].url, 'https://vscode.dev/callback');
for (const url of ['https://github.com/login', 'https://insiders.vscode.dev/tunnel/host', 'http://vscode.dev/']) {
  const wrongParent = page(url);
  assert.equal(vm.runInNewContext(deliver, wrongParent.context), false);
  assert.equal(wrongParent.entries.size, 0);
  assert.equal(wrongParent.events.length, 0);
}
console.log('PASS: delayed callbacks, opaque request IDs, both callback keys, origin guards, ciphertext escaping and storage events');
