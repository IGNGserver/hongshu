import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

function runtime() {
  const ctx=vm.createContext({URLSearchParams,Uint8Array,AbortController,DOMException,console});
  vm.runInContext(readFileSync(new URL('../app.js',import.meta.url),'utf8').replace(/^export /gm,''),ctx);
  vm.runInContext(`document={querySelector:()=>null};setBusy=()=>{};renderDetail=()=>{};
    state.me={cursor:200,epoch:1};state.cursor=200;state.epoch=1;state.epochSeen=true;
     state.sender='synthetic';
    state.messages=Array.from({length:200},(_,i)=>({id:i+1,timestamp:i+1}));
    state.messagesQuery=historyQueryKey();state.conversationsQuery=inboxQueryKey();`,ctx);
  return ctx;
}
test('idle polling preserves older pages and never downloads discarded sync bodies',async()=>{
  const ctx=runtime();
  vm.runInContext(`calls=[];refreshes=0;
    api=async(path)=>{calls.push(path);return path==='/me'?{cursor:200,epoch:1}:path==='/devices'?{devices:[]}:path==='/sims'?{sims:[]}:{contacts:{}}};
    refreshInbox=async()=>{refreshes++};`,ctx);
  await vm.runInContext('catchUp()',ctx);
  assert.equal(vm.runInContext('state.messages.length',ctx),200);
  assert.equal(vm.runInContext('refreshes',ctx),0);
  assert.equal(vm.runInContext("calls.some(p=>p.startsWith('/sync'))",ctx),false);
});
test('late history cannot repopulate logged-out state even if transport ignores cancellation',async()=>{
  const ctx=runtime();
  vm.runInContext(`api=()=>new Promise(resolve=>{resolveHistory=resolve});pending=loadHistory(false);disconnect();resolveHistory({messages:[{id:1,timestamp:1}]});`,ctx);
  await vm.runInContext('pending',ctx);
  assert.equal(vm.runInContext('state.messages.length',ctx),0);
});
test('API aborts transport and rejects a stale session response',async()=>{
  const ctx=runtime();
  vm.runInContext(`fetch=(_path,options)=>new Promise(resolve=>{requestSignal=options.signal;resolveFetch=resolve});pending=api('/me');disconnect();resolveFetch({ok:true,json:async()=>({cursor:1})});`,ctx);
  await assert.rejects(vm.runInContext('pending',ctx),err=>err.name==='AbortError');
  assert.equal(vm.runInContext('requestSignal.aborted',ctx),true);
});
test('real changes preserve loaded range and replace the settings snapshot',async()=>{
  const ctx=runtime();
  vm.runInContext(`api=async(path)=>path==='/me'?{cursor:201,epoch:1,title:'new title'}:path==='/devices'?{devices:[]}:path==='/sims'?{sims:[]}:path==='/contacts'?{contacts:{}}:{messages:[{id:201,timestamp:201}]};refreshInbox=async options=>loadHistory(false,{preserve:options.preserveHistory});`,ctx);
  await vm.runInContext('catchUp()',ctx);
  assert.equal(vm.runInContext('state.messages.length',ctx),201);
  assert.equal(vm.runInContext('state.me.title',ctx),'new title');
});
test('restore drops reused IDs and starts observing the restored head',async()=>{
  const ctx=runtime();
  vm.runInContext(`api=async(path)=>path==='/me'?{cursor:10,epoch:2}:path==='/devices'?{devices:[]}:path==='/sims'?{sims:[]}:{contacts:{}};refreshInbox=async()=>{};`,ctx);
  await vm.runInContext('catchUp()',ctx);
  assert.equal(vm.runInContext('state.messages.length',ctx),0);
  assert.equal(vm.runInContext('state.cursor',ctx),10);
});
test('classification updates revalidate older displayed pages without retaining moved messages',async()=>{
  const ctx=runtime();
  vm.runInContext(`state.me.analysis_revision=1;
    api=async(path)=>path==='/me'?{cursor:200,epoch:1,analysis_revision:2}:path==='/devices'?{devices:[]}:path==='/sims'?{sims:[]}:path==='/contacts'?{contacts:{}}:path==='/analysis/settings'?{enabled:true}:path.includes('before=')?{messages:Array.from({length:99},(_,i)=>({id:99-i,timestamp:99-i}))}:{messages:Array.from({length:100},(_,i)=>({id:200-i,timestamp:200-i}))};
    refreshInbox=async options=>loadHistory(false,{preserve:options.preserveHistory,revalidate:options.revalidateHistory});`,ctx);
  await vm.runInContext('catchUp()',ctx);
  assert.equal(vm.runInContext('state.messages.length',ctx),199);
  assert.equal(vm.runInContext('state.messages.some(m=>m.id===100)',ctx),false);
});
test('metadata event during a refresh is coalesced instead of dropping the final classification',async()=>{
  const ctx=runtime();
  vm.runInContext(`meCalls=0;state.me.analysis_revision=0;
    api=async(path)=>{if(path==='/me'){meCalls++;if(meCalls===1)return new Promise(resolve=>resolveHead=resolve);return {cursor:200,epoch:1,analysis_revision:2};}
      return path==='/devices'?{devices:[]}:path==='/sims'?{sims:[]}:path==='/contacts'?{contacts:{}}:{enabled:true};};
    refreshInbox=async()=>{};pending=catchUp();catchUp();resolveHead({cursor:200,epoch:1,analysis_revision:1});`,ctx);
  await vm.runInContext('pending',ctx);
  assert.equal(vm.runInContext('meCalls',ctx),2);
  assert.equal(vm.runInContext('state.me.analysis_revision',ctx),2);
});
test('a failed refresh stays dirty and is retried on the next otherwise empty poll',async()=>{
  const ctx=runtime();
  vm.runInContext(`api=async(path)=>path==='/me'?{cursor:201,epoch:1}:path==='/devices'?{devices:[]}:path==='/sims'?{sims:[]}:{contacts:{}};refreshes=0;refreshInbox=async()=>{if(++refreshes===1)throw Error('synthetic outage')};`,ctx);
  await assert.rejects(vm.runInContext('catchUp()',ctx));
  await vm.runInContext('catchUp()',ctx);
  assert.equal(vm.runInContext('refreshes',ctx),2);
  assert.equal(vm.runInContext('state.inboxDirty',ctx),false);
});
test('online shell updates offline cache without intercepting API',async()=>{
  const handlers={};const cache=new Map();let offline=false;
  const response=value=>({ok:true,value,clone(){return response(value)}});
  const ctx=vm.createContext({URL,self:{location:{origin:'https://example.test'},addEventListener:(event,handler)=>handlers[event]=handler},fetch:async()=>{if(offline)throw Error('offline');return response('new')},caches:{open:async()=>({put:async(request,value)=>cache.set(request.url,value)}),match:async request=>cache.get(request.url)}});
  vm.runInContext(readFileSync(new URL('../sw.js',import.meta.url),'utf8'),ctx);
  const request={method:'GET',url:'https://example.test/app.js'};let pending;
  handlers.fetch({request,respondWith:value=>pending=value});assert.equal((await pending).value,'new');
  offline=true;handlers.fetch({request,respondWith:value=>pending=value});assert.equal((await pending).value,'new');
  let intercepted=false;handlers.fetch({request:{method:'GET',url:'https://example.test/api/messages'},respondWith:()=>intercepted=true});assert.equal(intercepted,false);
});
