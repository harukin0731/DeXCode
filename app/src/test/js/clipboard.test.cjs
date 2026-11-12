const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = fs.readFileSync('app/src/main/assets/clipboard.js','utf8');
function fixture({allowed=true, selection='', nativeCopy=false, automatic=true}={}) {
  const calls=[], writes=[], dl={}, wl={}; let frames=[];
  const window={getSelection:()=>selection,addEventListener:(name,fn)=>{(wl[name]||=[]).push(fn);}}; window.parent=window;
  const document={activeElement:{},addEventListener:(name,fn)=>{(dl[name]||=[]).push(fn);},querySelectorAll:()=>frames,
    execCommand:command=>nativeCopy};
  const navigator={};
  const bridge={postMessage(raw){const value=JSON.parse(raw);calls.push(value);
    const permitted = allowed && (value.op !== 'select' || automatic);
    if(permitted&&['write','select'].includes(value.op))writes.push(value.text);
    queueMicrotask(()=>bridge.onmessage({data:JSON.stringify({id:value.id,ok:permitted,text:value.op==='read'?'clipboard text':value.op==='auto'?(automatic?'enabled':'disabled'):''})}));}};
  class ClipboardItem {constructor(data){this.types=Object.keys(data);this.getType=async type=>data[type];}}
  class DataTransfer {constructor(){this.values={};}setData(type,value){this.values[type]=value;}getData(type){return this.values[type]||'';}}
  class ClipboardEvent {constructor(type,options){this.type=type;Object.assign(this,options);}}
  const context={window,document,navigator,DOMException,Blob,ClipboardItem,DataTransfer,ClipboardEvent,KeyboardEvent:ClipboardEvent,Map,Promise,setTimeout,clearTimeout,queueMicrotask};
  window.DeXCodeClipboard=bridge;
  vm.runInNewContext(script,context);
  return {window,document,navigator,calls,writes,dl,wl,context,setFrames:values=>{frames=values;},
    emit:async event=>{for(const fn of wl.message||[])fn(event);await new Promise(resolve=>setImmediate(resolve));}};
}
let count=0;
async function test(name,fn){await fn();count++;console.log('PASS:',name);}
(async()=>{
await test('clipboard API is installed before page code and preserves Unicode',async()=>{
 const f=fixture();await f.navigator.clipboard.writeText('日本語\nagent output');
 assert.deepEqual(f.writes,['日本語\nagent output']);assert.equal(await f.navigator.clipboard.readText(),'clipboard text');
});
await test('promise-backed ClipboardItem text writes reach native clipboard',async()=>{
 const f=fixture();await f.navigator.clipboard.write([{types:['text/plain'],getType:async()=>new Blob(['agent code'])}]);
 assert.deepEqual(f.writes,['agent code']);
});
await test('legacy execCommand copy uses selected text',async()=>{
 const f=fixture({selection:'legacy output'});assert.equal(f.document.execCommand('copy'),true);
 await new Promise(resolve=>setImmediate(resolve));assert.deepEqual(f.writes,['legacy output']);
});
await test('copy-event payload is captured after application handlers',async()=>{
 const f=fixture();let text='';const event={clipboardData:{getData:()=>text}};
 f.dl.copy[0](event);text='app-provided payload';await new Promise(resolve=>setImmediate(resolve));
 assert.deepEqual(f.writes,['app-provided payload']);
});
await test('password input selections are not used for legacy copy',async()=>{
 const f=fixture();f.document.activeElement={type:'password',value:'private',selectionStart:0,selectionEnd:7};
 assert.equal(f.document.execCommand('copy'),false);assert.equal(f.writes.length,0);
});
await test('workbench iframe can relay writes but cannot read through its parent',async()=>{
 const parent=fixture(),child=fixture({allowed:false});child.window.parent=parent.window;
 parent.setFrames([{contentWindow:child.window}]);
 parent.window.postMessage=data=>{parent.emit({data,source:child.window});};
 child.window.postMessage=data=>{child.emit({data,source:parent.window});};
 await child.navigator.clipboard.writeText('iframe agent answer');assert.deepEqual(parent.writes,['iframe agent answer']);
 await assert.rejects(child.navigator.clipboard.readText());assert.equal(parent.calls.filter(c=>c.op==='read').length,0);
});
await test('unrelated windows and forged read relay messages are rejected',async()=>{
 const parent=fixture();await parent.emit({data:{kind:'dexcode-copy-write',id:1,text:'bad'},source:{}});
 await parent.emit({data:{kind:'dexcode-copy-read',id:2},source:{}});assert.equal(parent.calls.length,0);
});
await test('reinjection does not wrap clipboard repeatedly',async()=>{
 const f=fixture();const write=f.navigator.clipboard.writeText;vm.runInNewContext(script,f.context);
 assert.equal(f.navigator.clipboard.writeText,write);await write('one');assert.equal(f.writes.length,1);
});
const waitSelection=()=>new Promise(resolve=>setTimeout(resolve,170));
const chat={closest:selector=>selector.includes('.chat-widget')?chat:null};
await test('agent selection is copied after a trusted pointer release, not during drag',async()=>{
 const f=fixture({selection:'selected agent text'});
 f.dl.pointerdown[0]({isTrusted:true,target:chat});f.dl.selectionchange[0]();await waitSelection();assert.equal(f.writes.length,0);
 f.dl.pointerup[0]({isTrusted:true,target:chat});await waitSelection();assert.deepEqual(f.writes,['selected agent text']);
 assert.equal(f.calls[0].op,'select');
});
await test('canvas terminal selection is obtained through its copy handler without keystrokes',async()=>{
 const f=fixture();const terminal={closest:selector=>selector==='.xterm'?terminal:null,
  dispatchEvent:event=>{event.clipboardData.setData('text/plain','Codex terminal output');f.dl.copy[0](event);}};
 f.dl.pointerdown[0]({isTrusted:true,target:terminal});f.dl.pointerup[0]({isTrusted:true,target:terminal});
 await waitSelection();assert.deepEqual(f.writes,['Codex terminal output']);
});
await test('terminal rerender can detach the original glyph span during selection',async()=>{
 const f=fixture();let attached=true;
 const terminal={isConnected:true,closest:selector=>selector==='.xterm'?terminal:null,
  dispatchEvent:event=>event.clipboardData.setData('text/plain','shell selection after rerender')};
 const glyph={closest:selector=>attached&&selector==='.xterm'?terminal:null};
 f.dl.pointerdown[0]({isTrusted:true,target:glyph});attached=false;
 // Mouse release may be over a different element or outside the original terminal.
 f.dl.pointerup[0]({isTrusted:true,target:{closest:()=>null}});
 await waitSelection();assert.deepEqual(f.writes,['shell selection after rerender']);
});
await test('editor-area terminals are copied before Monaco source-editor exclusions',async()=>{
 const f=fixture();const terminal={isConnected:true,getClientRects:()=>[{}],
  closest:selector=>['.xterm','.terminal-editor','.monaco-editor'].includes(selector)?terminal:null,
  dispatchEvent:event=>event.clipboardData.setData('text/plain','editor terminal text')};
 const down={isTrusted:true,target:terminal},up={isTrusted:true,target:terminal};
 f.wl.pointerdown[0](down);f.dl.pointerdown[0](down);
 f.wl.pointerup[0](up); // workbench consumes the event before document receives it
 await waitSelection();assert.deepEqual(f.writes,['editor terminal text']);
 assert.equal(f.window.__dexcodeClipboardDiagnostics().region,'editor-terminal');
 assert.equal(f.window.__dexcodeClipboardDiagnostics().gestures,1);
});
await test('mouse fallback and terminal helper textarea provide selection without pointer events',async()=>{
 const f=fixture();const input={dispatchEvent:event=>event.clipboardData.setData('text/plain','helper selection')};
 const terminal={closest:selector=>selector==='.xterm'?terminal:null,querySelector:()=>input};
 f.wl.mousedown[0]({isTrusted:true,target:terminal});f.wl.mouseup[0]({isTrusted:true,target:terminal});
 await waitSelection();assert.deepEqual(f.writes,['helper selection']);
});
await test('manual terminal copy works with automatic copy disabled',async()=>{
 const f=fixture({automatic:false});const terminal={isConnected:true,getClientRects:()=>[{}],
  closest:selector=>selector==='.xterm'?terminal:null,
  dispatchEvent:event=>event.clipboardData.setData('text/plain','manual terminal text')};
 f.setFrames([terminal]);
 assert.equal(await f.window.__dexcodeCopyTerminalSelection(),'written');assert.deepEqual(f.writes,['manual terminal text']);
 assert.equal(f.calls[0].op,'write');
 const diagnostics=f.window.__dexcodeClipboardDiagnostics();
 assert.equal(diagnostics.characters,20);assert.equal(JSON.stringify(diagnostics).includes('manual terminal text'),false);
});
await test('empty terminal click never injects a key or changes focus',async()=>{
 const f=fixture();let focus=0;const events=[];
 const input={focus(){focus++;},dispatchEvent:event=>events.push(event)};
 const root={isConnected:true,getClientRects:()=>[{}],querySelector:()=>input,
  closest:selector=>selector==='.xterm'?root:null};
 for(let i=0;i<3;i++){
  if(i===1)f.window.__dexcodeSetTerminalSelectionMode(true);
  f.wl.pointerdown[0]({isTrusted:true,target:root});f.wl.pointerup[0]({isTrusted:true,target:root});await waitSelection();
 }
 assert.equal(focus,0);assert.equal(events.filter(e=>e.type==='keydown'||e.type==='keyup').length,0);
 assert.equal(f.writes.length,0);assert.equal(f.window.__dexcodeClipboardDiagnostics().outcome,'no_selection');
 assert.equal(f.window.__dexcodeClipboardDiagnostics().fallbackRequests,0);
});
await test('explicit Copy with no terminal selection never sends a terminal key',async()=>{
 const f=fixture({automatic:false});let keys=0;
 const input={focus(){throw Error('Unexpected focus');},dispatchEvent:event=>{if(event.type==='keydown'||event.type==='keyup')keys++;}};
 const root={isConnected:true,getClientRects:()=>[{}],querySelector:()=>input,closest:selector=>selector==='.xterm'?root:null};f.setFrames([root]);
 assert.equal(await f.window.__dexcodeCopyTerminalSelection(),'no_selection');assert.equal(keys,0);assert.deepEqual(f.writes,[]);
});
await test('selection mode forces Shift only for trusted terminal mouse gestures',async()=>{
 const f=fixture();const root={closest:selector=>selector==='.xterm'?root:null,
  dispatchEvent:event=>event.clipboardData.setData('text/plain','forced selection')};
 f.window.__dexcodeSetTerminalSelectionMode(true);
 const down={isTrusted:true,target:root,shiftKey:false,ctrlKey:false};f.wl.pointerdown[0](down);
 assert.equal(down.shiftKey,true);assert.equal(down.ctrlKey,false);assert.equal(down.getModifierState('Shift'),true);
 const move={isTrusted:true,target:root,shiftKey:false};f.wl.mousemove[0](move);assert.equal(move.shiftKey,true);
 const up={isTrusted:true,target:root,shiftKey:false};f.wl.mouseup[0](up);assert.equal(up.shiftKey,true);
 await waitSelection();assert.deepEqual(f.writes,['forced selection']);
});
await test('selection mode leaves TUI mouse gestures unchanged when disabled',async()=>{
 const f=fixture();const root={closest:selector=>selector==='.xterm'?root:null};
 const event={isTrusted:true,target:root,shiftKey:false};f.wl.pointerdown[0](event);assert.equal(event.shiftKey,false);
});
await test('selection mode does not force modifiers in source editors',async()=>{
 const f=fixture();f.window.__dexcodeSetTerminalSelectionMode(true);
 const editor={closest:selector=>selector==='.monaco-editor'?editor:null};
 const event={isTrusted:true,target:editor,shiftKey:false};f.wl.pointerdown[0](event);assert.equal(event.shiftKey,false);
});
await test('source editor selection and synthetic pointer events do not auto-copy',async()=>{
 const f=fixture({selection:'code selection'});const editor={closest:selector=>selector==='.monaco-editor'?editor:null};
 f.dl.pointerdown[0]({isTrusted:true,target:editor});f.dl.pointerup[0]({isTrusted:true,target:editor});await waitSelection();
 f.dl.pointerdown[0]({isTrusted:false,target:chat});f.dl.pointerup[0]({isTrusted:false,target:chat});await waitSelection();
 assert.equal(f.writes.length,0);
});
await test('automatic-copy preference does not disable explicit clipboard copying',async()=>{
 const f=fixture({selection:'auto',automatic:false});f.dl.pointerdown[0]({isTrusted:true,target:chat});
 f.dl.pointerup[0]({isTrusted:true,target:chat});await waitSelection();assert.equal(f.writes.length,0);
 await f.navigator.clipboard.writeText('manual');assert.deepEqual(f.writes,['manual']);
});
await test('keyboard selections are copied and collapsed selections preserve clipboard',async()=>{
 const f=fixture({selection:'keyboard selection'});f.dl.keyup[0]({isTrusted:true,target:chat,shiftKey:true,key:'ArrowRight'});
 await waitSelection();assert.deepEqual(f.writes,['keyboard selection']);
 const empty=fixture();empty.dl.pointerdown[0]({isTrusted:true,target:chat});empty.dl.pointerup[0]({isTrusted:true,target:chat});
 await waitSelection();assert.equal(empty.writes.length,0);
});
console.log(`${count} clipboard checks passed`);
})().catch(error=>{console.error(error);process.exitCode=1;});
