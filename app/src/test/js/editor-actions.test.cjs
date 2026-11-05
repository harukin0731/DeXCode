const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('app/src/main/assets/editor-actions.js', 'utf8');
let cases = 0;
function test(name, fn) { fn(); cases++; console.log('PASS:', name); }
function fixture({ ready = true, button = true, input = false, hidden = false, responds = true } = {}) {
    const events = [];
    let opened = input, terminal = false, clicks = 0;
    class Input { constructor() { this._value = 'old query'; } }
    Object.defineProperty(Input.prototype, 'value', { get() { return this._value; }, set(v) { this._value = v; } });
    const field = Object.assign(new Input(), {
        getClientRects: () => opened && !hidden ? [{}] : [],
        dispatchEvent: e => { events.push(e); return true; }, focus() { this.focused = true; }
    });
    const opener = { getClientRects: () => [{}], click() { clicks++; if (responds) opened = true; } };
    const pane = { getClientRects: () => terminal ? [{}] : [] };
    const workbench = {
        getClientRects: () => [{}], contains: e => e === field,
        querySelectorAll(selector) {
            if (selector.includes('quick-input-widget')) return [field];
            if (selector.includes('.terminal')) return [pane];
            return button ? [opener] : [];
        },
        dispatchEvent(e) {
            events.push(e);
            if (!responds || e.type !== 'keydown') return true;
            if (e.code === 'F1' || e.code === 'KeyP') opened = true;
            if (e.code === 'Backquote') terminal = !terminal;
            return true;
        }
    };
    class Event { constructor(type, fields = {}) { this.type = type; Object.assign(this, fields); } }
    const context = { document: { querySelector: () => ready ? workbench : null, activeElement: {} },
        getComputedStyle: () => ({ visibility: 'visible', display: 'block' }),
        HTMLInputElement: Input, Event, KeyboardEvent: Event, window: {} };
    const run = vm.runInNewContext(source, context);
    return { run, events, field, clicks: () => clicks };
}
test('login or unloaded page is rejected without injecting keyboard input', () => {
    const f = fixture({ ready: false });
    assert.equal(f.run('commands', 0), 'not-ready'); assert.equal(f.events.length, 0);
});
test('native command-center click opens palette mode and emits input event', () => {
    const f = fixture();
    assert.equal(f.run('commands', 0), 'sent'); assert.equal(f.clicks(), 1);
    assert.equal(f.run('commands', 2), 'ok'); assert.equal(f.field.value, '>');
    assert.equal(f.events[0].type, 'input'); assert.equal(f.events[0].bubbles, true); assert.equal(f.field.focused, true);
});
test('file search clears command mode only in the quick-input field', () => {
    const f = fixture({ input: true });
    f.field.value = '>old'; f.run('files', 0);
    assert.equal(f.clicks(), 0); assert.equal(f.run('files', 2), 'ok'); assert.equal(f.field.value, '');
});
test('F1 fallback supplies VS Code numeric codes and bubbling events', () => {
    const f = fixture({ button: false });
    f.run('commands', 1);
    const e = f.events[0];
    assert.equal(e.key, 'F1'); assert.equal(e.code, 'F1'); assert.equal(e.keyCode, 112);
    assert.equal(e.which, 112); assert.equal(e.bubbles, true); assert.equal(e.cancelable, true);
    assert.equal(f.events[1].type, 'keyup'); assert.equal(f.run('commands', 2), 'ok');
});
test('file-search fallback sends Ctrl+P with legacy numeric fields', () => {
    const f = fixture({ button: false }); f.run('files', 0);
    assert.equal(f.events[0].ctrlKey, true); assert.equal(f.events[0].keyCode, 80);
    assert.equal(f.run('files', 2), 'ok');
});
test('nonresponsive controls and hidden fields are never reported as success', () => {
    const f = fixture({ responds: false }); f.run('commands', 0);
    assert.equal(f.run('commands', 2), 'unavailable');
    const hidden = fixture({ input: true, hidden: true });
    assert.equal(hidden.run('commands', 2), 'unavailable');
});
test('terminal requires an observed visibility change', () => {
    const f = fixture(); f.run('terminal', 0); assert.equal(f.run('terminal', 2), 'ok');
    const ignored = fixture({ responds: false }); ignored.run('terminal', 0);
    assert.equal(ignored.run('terminal', 2), 'unavailable');
});
console.log(`${cases} editor-control checks passed`);
