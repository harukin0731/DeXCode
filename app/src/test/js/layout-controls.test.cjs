const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const source = fs.readFileSync('app/src/main/assets/layout-controls.js', 'utf8');
let checks = 0;
for (const action of ['sidebar', 'panel', 'auxiliary']) {
    for (const direct of [true, false, 'missing', 'unresponsive']) {
        let shown = false, keys = 0, clicks = 0;
        const region = {getClientRects: () => shown ? [{}] : []};
        const button = {getClientRects: () => [{}], getAttribute: () => null,
            dispatchEvent(event) { assert.equal(event.type, 'click'); assert.ok(event.view);
                clicks++; if (direct === true) shown = !shown; }};
        const target = {dispatchEvent(event) {
            if (event.type !== 'keydown') return;
            assert.equal(event.ctrlKey, true);
            assert.equal(event.altKey, action === 'auxiliary');
            assert.equal(event.key, action === 'panel' ? 'j' : 'b');
            keys++; if (direct !== 'unresponsive') shown = !shown;
        }};
        const workbench = {contains: () => true, querySelectorAll: () => [], querySelector: selector => selector.includes('titlebar') ? (direct === 'missing' ? null : button) : region};
        const run = vm.runInNewContext(source, {window: {}, document: {querySelector: () => workbench, activeElement: target},
            getComputedStyle: () => ({display:'block', visibility:'visible'}),
            MouseEvent: class {constructor(type, options) {this.type = type; Object.assign(this, options);}},
            KeyboardEvent: class {constructor(type, options) {this.type = type; Object.assign(this, options);}}});
        for (const expected of [true, false]) {
            assert.equal(run(action, 0, direct === 'missing' ? 0 : 7), 'sent');
            const result = run(action, 1, 7);
            assert.equal(result, direct === true ? 'ok' : 'sent');
            assert.equal(run(action, 2, 7), direct === 'unresponsive' ? 'unavailable' : 'ok');
            assert.equal(shown, direct === 'unresponsive' ? false : expected);
        }
        assert.equal(clicks, direct === 'missing' ? 0 : 2);
        assert.equal(keys, direct === true ? 0 : 2);
        checks++;
        console.log(`PASS: ${action} opens and closes via ${direct === true ? 'original action without double toggle' : direct === false ? 'verified keyboard fallback' : direct === 'missing' ? 'fixed action without any titlebar button' : 'failed action reported unavailable'}`);
    }
}
const run = vm.runInNewContext(source, {document:{querySelector: () => null}});
assert.equal(run('panel',0,1), 'unavailable');

// Captured titlebar icon names from real code-server builds, including hidden-state aliases.
for (const [action, icons] of Object.entries({
    sidebar: ['layout-sidebar-left','layout-sidebar-left-off','panel-left','panel-left-off','panel-right','panel-right-off'],
    panel: ['layout-panel','layout-panel-off','panel-layout-icon','panel-layout-icon-off'],
    auxiliary: ['layout-sidebar-right','layout-sidebar-right-off','auxiliarybar-left-layout-icon','auxiliarybar-right-layout-icon','auxiliarybar-left-off-layout-icon','auxiliarybar-right-off-layout-icon']
})) {
    for (const icon of icons) {
        let shown = false;
        const region = {getClientRects: () => shown ? [{}] : []};
        const button = {getClientRects: () => [{}], getAttribute: () => null,
            dispatchEvent(event) {assert.equal(event.type,'click');assert.ok(event.view);shown=!shown;}};
        const workbench = {querySelector: selector => selector.includes('titlebar') ? null : region,
            querySelectorAll: selector => selector.split(',').includes('.part.titlebar .codicon-'+icon) ? [button] : []};
        const run=vm.runInNewContext(source,{window:{},document:{querySelector:()=>workbench},
            getComputedStyle:()=>({display:'block',visibility:'visible'}),
            MouseEvent:class {constructor(type,options){this.type=type;Object.assign(this,options);}},
            KeyboardEvent:class {constructor(){throw Error('Direct layout control must not need keyboard input');}}});
        for (const expected of [true,false]) {
            assert.equal(run(action,0,0),'sent');assert.equal(run(action,1,0),'ok');assert.equal(shown,expected);
        }
        checks++;
        console.log('PASS: fixed layout control resolves '+icon+' without snapshot ID or keybindings');
    }
}

console.log(`${checks + 1} layout checks passed`);
