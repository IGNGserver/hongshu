import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { NetworkProfile } from "../app.js";

function runtime(extra = {}) {
  const ctx = vm.createContext({ URLSearchParams, Uint8Array, AbortController, DOMException, console, ...extra });
  vm.runInContext(readFileSync(new URL("../app.js", import.meta.url), "utf8").replace(/^export /gm, ""), ctx);
  vm.runInContext(`
    document={querySelector:()=>null,visibilityState:'visible'};
    setBusy=()=>{};renderGroupBar=()=>{};renderConversations=()=>{rendered++};renderDetail=()=>0;renderFilterMenu=()=>{};
    state.me={device:{id:'synthetic'},cursor:1,epoch:1,metadata_revision:1,features:['metadata_revision']};
    state.cursor=1;state.epoch=1;state.epochSeen=true;rendered=0;calls=[];
  `, ctx);
  return ctx;
}
const run = (ctx, code) => vm.runInContext(code, ctx);

test("slow mode uses bounded timing history with hysteresis", () => {
  const p = new NetworkProfile();
  p.observe(1300); p.observe(1300); assert.equal(p.constrained, false);
  p.observe(1300); assert.equal(p.constrained, true);
  for (let i = 0; i < 4; i++) p.observe(100);
  assert.equal(p.constrained, true);
  p.observe(100); assert.equal(p.constrained, false);
  for (let i = 0; i < 50; i++) p.observe(100);
  assert.equal(p.samples.length, 8);
  p.manual = true; assert.equal(p.constrained, true);
});

test("inbox resolves and renders while statistics never complete", async () => {
  const ctx = runtime();
  run(ctx, `api=async path=>{
    calls.push(path);
    if(path.startsWith('/conversations'))return {conversations:[{sender:'synthetic',id:1}]};
    if(path.startsWith('/analysis/counts'))return new Promise(()=>{});
    throw Error('unexpected first-screen dependency');
  };`);
  assert.equal(await run(ctx, "refreshInbox()"), true);
  assert.equal(run(ctx, "state.conversationsLoaded && rendered>0"), true);
  assert.equal(run(ctx, "calls.filter(p=>p.startsWith('/devices')||p.startsWith('/analysis/settings')).length"), 0);
});

test("metadata is single-flight, revision-aware, and never reads the AI queue", async () => {
  const ctx = runtime();
  run(ctx, `api=path=>{calls.push(path);return new Promise(r=>release=r)};a=reloadMetadata();b=reloadMetadata();`);
  assert.equal(run(ctx, "calls.length"), 1);
  run(ctx, "release({revision:1,devices:[],sims:[],contacts:{}})");
  await run(ctx, "Promise.all([a,b])");
  await run(ctx, "reloadMetadata()");
  assert.equal(run(ctx, "calls.length"), 1);
  run(ctx, "state.me.metadata_revision=2;c=reloadMetadata();release({revision:2,unchanged:false,devices:[],sims:[],contacts:{}})");
  await run(ctx, "c");
  assert.equal(run(ctx, "calls.length"), 2);
});

test("late metadata cannot populate a replacement identity", async () => {
  const ctx = runtime();
  run(ctx, "api=()=>new Promise(r=>release=r);pending=reloadMetadata();disconnect();state.me={device:{id:'replacement'}};release({revision:1,devices:[{id:'old'}],sims:[],contacts:{private:'old'}})");
  await run(ctx, "pending");
  assert.equal(run(ctx, "state.devices.length"), 0);
  assert.equal(run(ctx, "Object.keys(state.contacts).length"), 0);
});

test("timeout aborts the actual transport and does not revoke an identity", async () => {
  let expire;
  const ctx = runtime({ setTimeout: fn => { expire = fn; return 1; }, clearTimeout: () => {} });
  run(ctx, "fetch=(_p,o)=>new Promise((_r,reject)=>o.signal.addEventListener('abort',()=>reject(new DOMException('aborted','AbortError'))));pending=api('/me')");
  expire();
  await assert.rejects(run(ctx, "pending"), error => error.name === "TimeoutError");
  assert.equal(run(ctx, "state.me.device.id"), "synthetic");
});

test("a hanging metadata request cannot block changed content", async () => {
  const ctx = runtime();
  run(ctx, `state.me.analysis_revision=1;state.me.ai_enabled=false;
    api=async path=>{
      if(path==='/me')return {...state.me,cursor:2};
      if(path.startsWith('/metadata'))return new Promise(()=>{});
      if(path.startsWith('/conversations'))return {conversations:[{sender:'synthetic',id:2}]};
      if(path.startsWith('/analysis/counts'))return {counts:{}};
      throw Error('unexpected');
    };
  `);
  await run(ctx, "catchUp()");
  assert.equal(run(ctx, "state.conversations[0].id"), 2);
});

test("startup exposes its shell after authentication and content in the second API stage", async () => {
  const ctx = runtime();
  run(ctx, `navigator={};shell=()=>{shellReady=true;contentTask=refreshInbox()};shellReady=false;realtime=()=>{};browserPushEnabled=async()=>false;
    api=path=>{calls.push(path);return new Promise(resolve=>{
      if(path==='/me')releaseMe=resolve;
      else if(path.startsWith('/conversations'))releaseContent=resolve;
      else if(path.startsWith('/analysis/counts'))resolve({counts:{}});
    })};pending=start();`);
  assert.deepEqual(Array.from(run(ctx, "calls")), ["/me"]);
  run(ctx, "releaseMe({...state.me})");
  for (let i = 0; i < 8; i++) await Promise.resolve();
  assert.equal(run(ctx, "shellReady"), true);
  assert.equal(run(ctx, "calls.filter(p=>p.startsWith('/conversations')).length"), 1);
  run(ctx, "releaseContent({conversations:[{id:1,sender:'synthetic'}]})");
  await run(ctx, "Promise.all([pending,contentTask])");
  assert.equal(run(ctx, "rendered>0"), true);
  assert.equal(run(ctx, "calls.some(p=>p==='/analysis/settings')"), false);
});

test("metadata from an earlier epoch is ignored even if a hub reuses the revision", async () => {
  const ctx = runtime();
  run(ctx, "api=()=>new Promise(r=>release=r);pending=reloadMetadata();state.epoch=2;metadataRevision=null;metadataUpdatedAt=0;release({epoch:1,revision:1,devices:[{id:'old'}],sims:[],contacts:{}})");
  await run(ctx, "pending");
  assert.equal(run(ctx, "state.devices.length"), 0);
  assert.equal(run(ctx, "metadataUpdatedAt"), 0);
});

test("post-mutation refresh cannot join a pre-mutation metadata snapshot", async () => {
  const ctx = runtime();
  run(ctx, "api=p=>{calls.push(p);return new Promise(r=>release=r)};first=reloadMetadata();fresh=reloadMetadata({force:true});release({revision:1,devices:[],sims:[],contacts:{}})");
  await run(ctx, "first");
  for (let i = 0; i < 8; i++) await Promise.resolve();
  assert.equal(run(ctx, "calls.length"), 2);
  run(ctx, "release({revision:2,devices:[{id:'updated'}],sims:[],contacts:{}})");
  await run(ctx, "fresh");
  assert.equal(run(ctx, "state.devices[0].id"), "updated");
});

test("detail memory retains at most four recent threads and fifty messages each", () => {
  const ctx = runtime();
  run(ctx, `for(let i=0;i<10;i++){
    state.sender='synthetic-'+i;state.messagesQuery=historyQueryKey();
    state.messages=Array.from({length:200},(_,j)=>({id:j}));state.historyHasMore=false;rememberDetail();
  }`);
  assert.equal(run(ctx, "detailCache.size"), 4);
  assert.equal(run(ctx, "[...detailCache.values()].every(c=>c.messages.length===50&&c.more&&c.messages[0].id===150)"), true);
});
