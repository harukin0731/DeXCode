const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('app/src/main/assets/titlebar-controls.js', 'utf8');
let count = 0;
function test(name, run) { run(); count++; console.log('PASS:', name); }
function fixture({ready = true, top = 0, height = 35, commandCenter = false, titlebar = true, popupTop = null} = {}) {
    let clicks = 0;
    function control(label, icon, disabled = false) {
        return {dataset:{}, textContent:label, classList:[icon], disabled,
            getClientRects: () => [{}], getAttribute: name => name === 'aria-label' ? label : null,
            closest: () => null, contains: () => false, click() { clicks++; }};
    }
    const back = control('戻る', 'codicon-arrow-left');
    const panel = control('Toggle Panel', 'codicon-layout-panel');
    const disabled = control('Forward', 'codicon-arrow-right', true);
    const items = [back,panel,disabled];
    if (commandCenter) {
        const search = control('Search', 'codicon-search');
        search.closest = selector => selector === '.command-center-quick-pick' ? search : null;
        const agent = control('Chat', 'codicon-chat');
        agent.classList.contains = value => agent.classList.includes(value);
        agent.closest = selector => selector === '.command-center' ? agent : null;
        items.push(search, agent);
    }
    const bar = {dataset:{}, getClientRects: () => [{}],
        getBoundingClientRect: () => ({top, bottom:top+height, height}),
        querySelectorAll: () => items,
        querySelector: selector => items.find(e => selector.includes('"'+e.dataset.dexcodeControl+'"'))};
    const popup = {style:{},getClientRects:()=>[{}],getBoundingClientRect(){return {top: this.style.top ? parseFloat(this.style.top) : popupTop};}};
    const context = {document:{querySelector: selector => !ready ? null : selector === '.monaco-workbench' ? bar : titlebar && selector === '.monaco-workbench .part.titlebar' ? bar : null, querySelectorAll: () => popupTop === null ? [] : [popup]},
        window:{}, innerWidth:1000,getComputedStyle: () => ({visibility:'visible'})};
    return {run:vm.runInNewContext(source,context), clicks:() => clicks, back,panel,disabled,popup};
}
test('only the workbench titlebar is measured; labels and original controls are preserved', () => {
    const f = fixture(); const result = JSON.parse(f.run('snapshot',0));
    assert.equal(result.height,35); assert.equal(result.width,1000);
    assert.equal(result.controls[0].label,'戻る'); assert.equal(result.controls[1].icon,'codicon-layout-panel');
    assert.equal(result.controls[2].enabled,false);
});
test('search is represented by native search while other command-center actions are preserved', () => {
    const result = JSON.parse(fixture({commandCenter:true}).run('snapshot',0));
    assert.equal(result.controls.length,4);
    assert.equal(result.controls.some(c => c.label === 'Search'),false);
    assert.equal(result.controls.some(c => c.label === 'Chat'),true);
});
test('icons nested inside a labelled button retain their semantic action', () => {
    const f = fixture();
    f.panel.classList=[];
    f.panel.querySelector=()=>({classList:['codicon','codicon-layout-panel-off','codicon-modifier-disabled']});
    const result=JSON.parse(f.run('snapshot',0));
    assert.equal(result.controls.find(c=>c.label==='Toggle Panel').icon,'codicon-layout-panel-off');
});
test('native proxy invokes the original button once', () => {
    const f = fixture(); const result = JSON.parse(f.run('snapshot',0));
    assert.equal(f.run('click',result.controls[1].id),'sent'); assert.equal(f.clicks(),1);
});
test('disabled and stale IDs cannot invoke a different control', () => {
    const f = fixture(); const result = JSON.parse(f.run('snapshot',0));
    assert.equal(f.run('click',result.controls[2].id),'unavailable');
    assert.equal(f.run('click',10000),'unavailable'); assert.equal(f.clicks(),0);
});
test('repeated snapshots retain control IDs', () => {
    const f = fixture(); assert.equal(f.run('snapshot',0),f.run('snapshot',0));
});
test('non-topmost and implausibly tall bars are not cropped', () => {
    assert.equal(JSON.parse(fixture({top:40}).run('snapshot',0)).height,0);
    assert.equal(JSON.parse(fixture({height:400}).run('snapshot',0)).height,0);
});
test('login and unsupported pages preserve the entire viewport', () => {
    const result = JSON.parse(fixture({ready:false}).run('snapshot',0));
    assert.equal(result.height,0); assert.deepEqual(result.controls,[]);
});
test('fixed layout actions stay available without any original titlebar controls', () => {
    const result = JSON.parse(fixture({titlebar:false}).run('snapshot',0));
    assert.equal(result.ready,true);
    assert.equal(result.height,0);
    assert.equal(result.controls.length,0);
    assert.equal(JSON.parse(fixture({ready:false}).run('snapshot',0)).ready,false);
});
test('merged chrome keeps topmost quick input below the crop without moving centered popups', () => {
    const top=fixture({popupTop:6}); top.run('snapshot',1);
    assert.equal(top.popup.style.top,'41px');
    top.popup.style.top='6px'; top.run('snapshot',1);
    assert.equal(top.popup.style.top,'41px');
    const centered=fixture({popupTop:200}); centered.run('snapshot',1);
    assert.equal(centered.popup.style.top,undefined);
    const separate=fixture({popupTop:6}); separate.run('snapshot',0);
    assert.equal(separate.popup.style.top,undefined);
});
console.log(`${count} titlebar bridge checks passed`);
