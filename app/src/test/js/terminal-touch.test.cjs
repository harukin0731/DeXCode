const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync('app/src/main/assets/terminal-touch.js','utf8');
function fixture(){
 const handlers={};const screen={isConnected:true,getBoundingClientRect:()=>({left:10,top:20,width:300,height:200})};
 const target={closest:selector=>selector==='.xterm-screen'?screen:null};
 const window={scrollX:0,scrollY:0};const document={addEventListener:(type,fn)=>{(handlers[type]??=[]).push(fn)}};
 const context={window,document};vm.runInNewContext(source,context);
 const send=(type,fields={})=>{const event={target,translationX:0,translationY:20,preventDefault(){this.prevented=true},stopImmediatePropagation(){this.stopped=true},...fields};for(const fn of handlers[type]||[])fn(event);return event;};
 return {send,screen,handlers,context};
}
const f=fixture();
f.send('touchstart',{changedTouches:[{clientX:120,clientY:100}],touches:[{}]});
let event=f.send('-xterm-gesturechange');assert.equal(event.clientX,120);assert.equal(event.clientY,100);assert.equal(event.stopped,undefined);
event=f.send('-xterm-gesturechange',{clientX:150,clientY:160});assert.equal(event.clientX,150);assert.equal(event.clientY,160);
f.screen.getBoundingClientRect=()=>({left:10,top:20,width:50,height:40});event=f.send('-xterm-gesturechange');assert(event.clientX<60 && event.clientY<60);
event=f.send('-xterm-gesturechange',{translationY:NaN});assert.equal(event.stopped,true);
f.send('touchcancel');assert.equal(f.send('-xterm-gesturechange').stopped,true);
assert.equal(f.send('-xterm-gesturechange',{target:{closest:()=>null}}).stopped,undefined);
f.send('touchstart',{changedTouches:[{clientX:1,clientY:2}],touches:[{},{}]});assert.equal(f.send('-xterm-gesturechange').stopped,true);
vm.runInNewContext(source,f.context);assert.equal(f.handlers['-xterm-gesturechange'].length,1);
console.log('PASS: terminal touch inertia coordinates, valid gestures, resizing, invalid input, cancellation, other views, multitouch and idempotency');
