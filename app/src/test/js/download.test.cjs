const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const script=fs.readFileSync('app/src/main/assets/download.js','utf8');
function fixture({blob=new Blob(['file contents']),fetchFails=false,denyChunk=false}={}) {
 const messages=[],listeners={}; let nativeClicks=0, urlSequence=0;
 const bridge={postMessage(raw){const value=JSON.parse(raw);messages.push(value);
   queueMicrotask(()=>bridge.onmessage({data:JSON.stringify({id:value.id,ok:!(denyChunk&&value.op==='chunk')})}));}};
 const objectURL={createObjectURL:()=>`blob:https://editor.test/captured-${++urlSequence}`,revokeObjectURL:()=>{}};
 class HTMLAnchorElement {constructor(){this.download=undefined;this.href='';}hasAttribute(name){return name==='download'&&this.download!==undefined;}click(){nativeClicks++;}}
 const window={DeXCodeDownload:bridge,URL:objectURL};
 vm.runInNewContext(script,{window,document:{addEventListener:(name,fn)=>{listeners[name]=fn;}},Map,Promise,Uint8Array,HTMLAnchorElement,
  fetch:async()=>{if(fetchFails)throw Error('failed');return {ok:true,blob:async()=>blob};},
  btoa:binary=>Buffer.from(binary,'binary').toString('base64'),setTimeout,clearTimeout,Date,Math});
 return {window,messages,listeners,Anchor:HTMLAnchorElement,nativeClicks:()=>nativeClicks};
}
let count=0;async function test(name,fn){await fn();count++;console.log('PASS:',name);}
(async()=>{
 await test('blob downloads stream bounded chunks and preserve all bytes',async()=>{
  const bytes=Buffer.alloc(100123);for(let i=0;i<bytes.length;i++)bytes[i]=i%251;
  const f=fixture({blob:new Blob([bytes])});await f.window.__dexcodeDownload('blob:https://editor.test/a','file.bin','');
  const chunks=f.messages.filter(m=>m.op==='chunk').map(m=>Buffer.from(m.data,'base64'));
  assert.ok(chunks.every(b=>b.length<=49152));assert.deepEqual(Buffer.concat(chunks),bytes);
  assert.equal(f.messages[0].filename,'file.bin');assert.equal(f.messages.at(-1).op,'end');
 });
 await test('data downloads and zero-byte files finish without bogus chunks',async()=>{
  const f=fixture({blob:new Blob([])});await f.window.__dexcodeDownload('data:text/plain,','empty.txt','');
  assert.deepEqual(f.messages.map(m=>m.op),['begin','end']);
 });
 await test('fetch errors and oversized blobs abort without starting a transfer',async()=>{
  for(const config of [{fetchFails:true},{blob:{size:134217729}}]){
   const f=fixture(config);await f.window.__dexcodeDownload('blob:https://e.test/a','x','');
   assert.deepEqual(f.messages.map(m=>m.op),['abort']);
  }
 });
 await test('native chunk failure aborts rather than saving a partial file',async()=>{
  const f=fixture({denyChunk:true});await f.window.__dexcodeDownload('blob:https://e.test/a','x','');
  assert.equal(f.messages.at(-1).op,'abort');assert.equal(f.messages.some(m=>m.op==='end'),false);
 });
 await test('blob download anchors are intercepted with their original filename',async()=>{
  const f=fixture();let prevented=false;
  f.listeners.click({target:{closest:()=>({href:'blob:https://e.test/a',download:'日本語.txt'})},preventDefault:()=>{prevented=true;}});
  await new Promise(resolve=>setImmediate(resolve));assert.equal(prevented,true);assert.equal(f.messages[0].filename,'日本語.txt');
 });
 async function completed(f, count=1) {
  for(let i=0;i<100;i++){if(f.messages.filter(m=>m.op==='end').length>=count)return;await new Promise(resolve=>setTimeout(resolve,2));}
  assert.fail('download did not complete');
 }
 await test('VS Code synthetic anchor survives immediate URL revocation and blocked fetch',async()=>{
  const f=fixture({fetchFails:true});const blob=new Blob(['package.json payload']);
  const url=f.window.URL.createObjectURL(blob);const anchor=new f.Anchor();anchor.download='package.json';anchor.href=url;
  anchor.click();f.window.URL.revokeObjectURL(url);await completed(f);
  assert.equal(f.nativeClicks(),0);assert.equal(f.messages[0].filename,'package.json');
  assert.equal(Buffer.from(f.messages.find(m=>m.op==='chunk').data,'base64').toString(),'package.json payload');
 });
 await test('multiple selected Explorer files are queued without losing revoked blobs',async()=>{
  const f=fixture({fetchFails:true});
  for(let i=0;i<3;i++){const url=f.window.URL.createObjectURL(new Blob([String(i)]));const a=new f.Anchor();a.download=`file${i}.txt`;a.href=url;a.click();f.window.URL.revokeObjectURL(url);}
  await completed(f,3);assert.deepEqual(f.messages.filter(m=>m.op==='begin').map(m=>m.filename),['file0.txt','file1.txt','file2.txt']);
  assert.equal(f.messages.some(m=>m.op==='abort'),false);
 });
 await test('ordinary HTTP anchors keep browser navigation',async()=>{
  const f=fixture();const anchor=new f.Anchor();anchor.download='a.txt';anchor.href='https://editor.test/a';anchor.click();
  assert.equal(f.nativeClicks(),1);assert.equal(f.messages.length,0);
 });
 await test('temporary URL and size failures carry actionable diagnostic codes',async()=>{
  const f=fixture({fetchFails:true});await f.window.__dexcodeDownload('blob:https://e.test/expired','a','');
  assert.equal(f.messages.at(-1).reason,'temporary_url_unavailable');assert.equal(f.messages.at(-1).phase,'source');
  const large=fixture({blob:{size:134217729}});await large.window.__dexcodeDownload('blob:https://e.test/large','a','');
  assert.equal(large.messages.at(-1).reason,'file_too_large');
 });
 console.log(`${count} download checks passed`);
})().catch(error=>{console.error(error);process.exitCode=1;});
