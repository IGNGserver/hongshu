import test from "node:test";
import assert from "node:assert/strict";
import vm from "node:vm";
import { hasCallLog, callSummary, callParty, callFacts, formatCallDuration, isMissedCall, mergeCallPages, appendCallPage, CALL_PAGE } from "../app.js";
import { runtimeSource } from "./runtime-source.js";

const call = (id, timestamp, extra = {}) => ({ id, timestamp, device_id: "d", receiver: "", number: "13900000000", contact: "", type: "incoming", duration: 0, subscription_id: -1, video: false, presentation: "allowed", location: "", historical: false, ...extra });

test("the call view exists only when the hub announces call_log", () => {
  assert.equal(hasCallLog({ features: ["outgoing_sms", "call_log"] }), true);
  assert.equal(hasCallLog({ features: ["outgoing_sms"] }), false);
  assert.equal(hasCallLog({}), false);
  assert.equal(hasCallLog(null), false);
});

test("durations read naturally and never go negative", () => {
  assert.equal(formatCallDuration(0), "0 秒");
  assert.equal(formatCallDuration(45), "45 秒");
  assert.equal(formatCallDuration(185), "3 分 5 秒");
  assert.equal(formatCallDuration(3725), "1 小时 2 分 5 秒");
  assert.equal(formatCallDuration(-5), "0 秒");
});

test("list summary says what happened, with a duration only when connected", () => {
  assert.equal(callSummary(call(1, 1, { type: "incoming", duration: 75 })), "呼入 · 1 分 15 秒");
  assert.equal(callSummary(call(1, 1, { type: "outgoing", duration: 0 })), "呼出 · 未接通");
  assert.equal(callSummary(call(1, 1, { type: "missed" })), "未接来电");
  assert.equal(callSummary(call(1, 1, { type: "rejected", duration: 3 })), "已拒接");
  assert.equal(callSummary(call(1, 1, { type: "outgoing", duration: 9, video: true })), "呼出 · 视频 · 9 秒");
  assert.equal(callSummary(call(1, 1, { type: "vendor_specific" })), "通话");
  for (const type of ["missed", "rejected", "blocked"]) assert.equal(isMissedCall({ type }), true);
  for (const type of ["incoming", "outgoing", "voicemail", "answered_externally"]) assert.equal(isMissedCall({ type }), false);
});

test("the party falls back from contact to number to the withheld reason", () => {
  assert.equal(callParty(call(1, 1), "合成联系人"), "合成联系人");
  assert.equal(callParty(call(1, 1)), "13900000000");
  assert.equal(callParty(call(1, 1, { number: "", presentation: "restricted" })), "隐藏号码");
  assert.equal(callParty(call(1, 1, { number: "", presentation: "payphone" })), "公用电话");
  assert.equal(callParty(call(1, 1, { number: "", presentation: "unknown" })), "未知号码");
});

test("detail facts name the line, device and history and omit empty fields", () => {
  const facts = new Map(callFacts(call(1, 1700000000000, { type: "outgoing", duration: 75, video: true, location: "合成归属地", historical: true }), { line: "SIM1 +8613800000000", device: "合成手机" }));
  assert.equal(facts.get("类型"), "呼出 · 视频通话");
  assert.equal(facts.get("通话时长"), "1 分 15 秒");
  assert.equal(facts.get("本机线路"), "SIM1 +8613800000000");
  assert.equal(facts.get("来源手机"), "合成手机");
  assert.equal(facts.get("归属地"), "合成归属地");
  assert.equal(facts.get("记录来源"), "开启同步前的历史记录");
  assert.match(facts.get("时间"), /2023/);
  const hidden = new Map(callFacts(call(2, 1700000000000, { type: "missed", number: "", presentation: "restricted" })));
  assert.equal(hidden.get("本机线路"), "未知线路");
  assert.equal(hidden.get("号码"), "隐藏号码");
  assert.equal(hidden.get("通话时长"), "未接通");
  assert.equal(hidden.has("归属地"), false);
  assert.equal(hidden.has("记录来源"), false);
});

test("a refreshed head keeps older scrolled pages without duplicates", () => {
  const loaded = Array.from({ length: 150 }, (_, i) => call(150 - i, 1000 - i));
  const head = [call(151, 2000), ...loaded.slice(0, CALL_PAGE - 1)];
  const merged = mergeCallPages(head, loaded, true);
  assert.equal(merged.length, 151);
  assert.equal(merged[0].id, 151);
  assert.equal(new Set(merged.map((c) => c.id)).size, 151);
  assert.deepEqual(mergeCallPages([call(1, 5)], loaded, false).map((c) => c.id), [1], "a short head is the whole list");
  // Equal start times order by the higher ID first.
  assert.deepEqual(appendCallPage([call(3, 10)], [call(2, 10), call(3, 10), call(4, 10)]).map((c) => c.id), [4, 3, 2]);
});

function runtime() {
  const ctx = vm.createContext({ URLSearchParams, Uint8Array, AbortController, DOMException, console, Intl });
  vm.runInContext(runtimeSource(), ctx);
  vm.runInContext(`document={querySelector:()=>null};setBusy=()=>{};
    state.me={cursor:200,epoch:1,call_cursor:3,features:['call_log']};state.cursor=200;state.epoch=1;state.epochSeen=true;state.callCursor=3;
    state.page='inbox';state.view='calls';state.calls=[{id:3,timestamp:30},{id:2,timestamp:20}];state.callsLoaded=true;`, ctx);
  return ctx;
}

test("a new call refreshes only the call list, leaving the SMS stream alone", async () => {
  const ctx = runtime();
  vm.runInContext(`paths=[];refreshes=0;refreshInbox=async()=>{refreshes++};
    api=async(path)=>{paths.push(path);return path==='/me'?{cursor:200,epoch:1,call_cursor:4,features:['call_log']}:path.startsWith('/calls?')?{calls:[{id:4,timestamp:40},{id:3,timestamp:30},{id:2,timestamp:20}],more:false,head:4,epoch:1}:path==='/devices'?{devices:[]}:path==='/sims'?{sims:[]}:{contacts:{}}};`, ctx);
  await vm.runInContext("catchUp()", ctx);
  assert.equal(vm.runInContext("state.calls.map(c=>c.id).join()", ctx), "4,3,2");
  assert.equal(vm.runInContext("state.callCursor", ctx), 4);
  assert.equal(vm.runInContext("state.callsDirty", ctx), false);
  assert.equal(vm.runInContext("refreshes", ctx), 0);
  assert.equal(vm.runInContext("paths.some(p=>p.startsWith('/sync')||p.startsWith('/conversations'))", ctx), false);
  // Idle: same head, no further call request.
  vm.runInContext("paths=[]", ctx);
  await vm.runInContext("catchUp()", ctx);
  assert.equal(vm.runInContext("paths.some(p=>p.startsWith('/calls'))", ctx), false);
});

test("a restore drops cached calls and a hub without call_log returns to SMS", async () => {
  const ctx = runtime();
  vm.runInContext(`refreshInbox=async()=>{};shell=()=>{};
    api=async(path)=>path==='/me'?{cursor:10,epoch:2,call_cursor:0,features:[]}:path==='/devices'?{devices:[]}:path==='/sims'?{sims:[]}:{contacts:{}};`, ctx);
  await vm.runInContext("catchUp()", ctx);
  assert.equal(vm.runInContext("state.calls.length", ctx), 0);
  assert.equal(vm.runInContext("state.view", ctx), "sms");
  assert.equal(vm.runInContext("state.callCursor", ctx), 0);
});

test("logging out forgets the call view and its records", () => {
  const ctx = runtime();
  vm.runInContext("state.callId=3;state.callDetail=state.calls[0];disconnect()", ctx);
  assert.equal(vm.runInContext("state.view+':'+state.calls.length+':'+state.callId+':'+state.callDetail", ctx), "sms:0:0:null");
});
