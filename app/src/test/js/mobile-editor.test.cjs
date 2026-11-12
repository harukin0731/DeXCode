const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync('app/src/main/assets/mobile-editor.js','utf8');
let count=0;
function test(name,run){run();count++;console.log('PASS:',name);}
function fixture(options={}) {
 const listeners={},windowListeners={},window={addEventListener(type,fn){(windowListeners[type]??=[]).push(fn);}};
 let doc,workbench;
 const editorRoot={};
 function element(o={}) {
  return {tagName:o.editable===false?'DIV':'TEXTAREA',type:o.password?'password':'text',readOnly:!!o.readOnly,disabled:!!o.disabled,
   classList:{contains:n=>n==='native-edit-context'&&!!o.native},isContentEditable:!!o.contentEditable,
   getClientRects:()=>o.hidden?[]:[{}],
   closest(s){
    if(s==='.monaco-workbench')return workbench;
    if(s.startsWith('.terminal,'))return o.terminal?{}:null;
    if(s==='.monaco-editor')return o.editor?editorRoot:null;
    if(s==='.part.editor .monaco-editor')return o.editor&&!o.sidebar?editorRoot:null;
    if(s.startsWith('.find-widget,'))return o.find?{}:null;
    if(s.startsWith('.quick-input-widget,'))return o.quick?{}:null;
    return null;
   },
   focus(){doc.activeElement=this;(listeners.focusin||[]).forEach(fn=>fn({target:this}));this.focused=true;}
  };
 }
 const active=element({editable:!!options.editor,...options});
 if(options.iframe)active.tagName='IFRAME';
 const target=element({editor:true});
 const preferred=element({editor:true,readOnly:options.preferredReadOnly});
 workbench={contains:e=>e!==null&&e!==undefined,
  querySelector:()=>options.group?{querySelectorAll:()=>options.emptyGroup?[]:[preferred]}:null,
  querySelectorAll:()=>options.candidate?[target]:[]};
 doc={activeElement:active,querySelector:()=>options.ready===false?null:workbench,
  addEventListener(type,fn){(listeners[type]??=[]).push(fn);}};
 const run=vm.runInNewContext(source,{window,document:doc,performance:{timeOrigin:123}});
 return {run,active,target,preferred,doc,window,listeners,
  event(type,target=active){(listeners[type]||[]).forEach(fn=>fn({target}));},
  blur(){(windowListeners.blur||[]).forEach(fn=>fn());}};
}
test('document-start listeners install before the workbench exists',()=>{
 const f=fixture({ready:false});assert.equal(f.run('install'),'ok');assert.equal(f.run('editor'),'not-ready');
 f.run('install');assert.equal(f.listeners.compositionstart.length,1);
});
test('indent and range selection require the document body, not its find widget',()=>{
 assert.equal(fixture({editor:true}).run('editor'),'ok');
 assert.equal(fixture({editor:true,find:true}).run('editor'),'tap-editor');
 assert.equal(fixture({editor:true,sidebar:true}).run('editor'),'tap-editor');
});
test('terminal rejects code shortcuts but permits its own paste command',()=>{
 const f=fixture({editor:true,terminal:true});for(const op of ['editor','read','text','save','navigation'])assert.equal(f.run(op),'tap-editor');
 assert.equal(f.run('keyboard'),'ok');assert.equal(f.run('paste'),'terminal');
});
test('copy paste and undo work in non-Monaco text fields',()=>{
 const f=fixture({editable:true,quick:true});assert.equal(f.run('read'),'ok');assert.equal(f.run('text'),'ok');
});
test('readonly inputs allow copy but reject edits',()=>{
 const f=fixture({editable:true,readOnly:true});assert.equal(f.run('read'),'ok');assert.equal(f.run('text'),'tap-editor');
});
test('password and iframe focus reject shortcuts without focusing a different editor',()=>{
 for(const args of [{password:true},{iframe:true}]){
  const f=fixture({...args,candidate:true});for(const op of ['editor','read','text','save','keyboard','navigation','paste'])assert.equal(f.run(op),'tap-editor');
  assert.equal(f.target.focused,undefined);
 }
});
test('save restores the active editor group from Explorer rather than the first DOM editor',()=>{
 const f=fixture({group:true,candidate:true});assert.equal(f.run('save'),'ok');assert.equal(f.preferred.focused,true);assert.equal(f.target.focused,undefined);
});
test('save does not dismiss a quick input or Save As dialog',()=>{
 const f=fixture({editable:true,quick:true,candidate:true});assert.equal(f.run('save'),'tap-editor');assert.equal(f.target.focused,undefined);
});
test('keyboard preserves the active input and otherwise prefers the active editor group',()=>{
 const active=fixture({editable:true,candidate:true});assert.equal(active.run('keyboard'),'ok');assert.equal(active.target.focused,undefined);
 const f=fixture({group:true,candidate:true});assert.equal(f.run('keyboard'),'ok');assert.equal(f.preferred.focused,true);
});
test('an active group without writable code never redirects to a different group',()=>{
 for(const options of [{preferredReadOnly:true},{emptyGroup:true}]){
  const f=fixture({group:true,candidate:true,...options});assert.equal(f.run('keyboard'),'tap-editor');assert.equal(f.run('save'),'tap-editor');assert.equal(f.target.focused,undefined);
 }
 assert.equal(fixture({editable:true,disabled:true}).run('keyboard'),'tap-editor');
});
test('composition blocks shortcuts and resumes after commit without moving focus',()=>{
 const f=fixture({editor:true});f.run('install');f.event('compositionstart');
 for(const op of ['editor','read','text','save','navigation','paste'])assert.equal(f.run(op),'composing');
 assert.equal(f.run('keyboard'),'ok');f.event('compositionend');assert.equal(f.run('editor'),'ok');
});
test('paste preserves the active terminal or text input instead of redirecting to code',()=>{
 for(const options of [{terminal:true,editable:true},{terminal:true,editor:true},{editor:true},{editable:true,quick:true}]){
  const f=fixture({...options,candidate:true});assert.equal(f.run('paste'),options.terminal?'terminal':'ok');
  assert.equal(f.doc.activeElement,f.active);assert.equal(f.target.focused,undefined);
 }
 for(const options of [{terminal:true,editable:true,readOnly:true},{terminal:true,editable:true,disabled:true},{terminal:true},{editable:true,readOnly:true},{}]){
  const f=fixture({...options,candidate:true});assert.equal(f.run('paste'),'tap-editor');assert.equal(f.target.focused,undefined);
 }
});
test('context token changes on editor switches, pointer movement and window blur',()=>{
 const f=fixture({editor:true});const before=f.run('context');f.event('focusin');const focus=f.run('context');assert.notEqual(before,focus);
 f.event('pointerdown');const pointer=f.run('context');assert.notEqual(pointer,focus);f.blur();assert.notEqual(f.run('context'),pointer);
});
console.log(`${count} mobile-editor checks passed`);
