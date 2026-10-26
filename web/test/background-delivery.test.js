import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { workerRuntime } from "./sw-harness.js";
import { runtimeSource } from "./runtime-source.js";

// Workbox keeps the module worker shell separate; exercise the push module on its
// own, including its public-message contract with existing window clients.
const pushWorker = readFileSync(new URL("../push.js", import.meta.url), "utf8");

test("push refreshes existing clients independently of WS without caching or fetching SMS", async () => {
  const messages = [];
  const runtime = workerRuntime(pushWorker, new Map(), undefined, { clients: [
    { postMessage: message => messages.push(message) },
    { postMessage: () => { throw Error("closed client"); } },
  ] });
  await runtime.push({ json: () => ({ tag: "hongshu-new" }) });
  assert.equal(runtime.notifications.length, 1);
  assert.equal(messages.length, 1);
  assert.equal(messages[0].type, "MESSAGES_CHANGED");
  assert.deepEqual(Object.keys(messages[0]), ["type"]);
  assert.equal(runtime.fetches, 0);
});

test("test notifications do not pretend that an SMS arrived", async () => {
  const runtime = workerRuntime(pushWorker, new Map(), undefined, { clients: [
    { postMessage: () => assert.fail("test notification must not refresh SMS") },
  ] });
  await runtime.push({ json: () => ({ tag: "hongshu-test" }) });
  assert.equal(runtime.notifications.length, 1);
});

test("a Push hint refreshes a signed-in page but never revives a logged-out session", async () => {
  const ctx = vm.createContext({ URLSearchParams, Uint8Array, AbortController, DOMException, console, atob });
  vm.runInContext(runtimeSource(), ctx);
  vm.runInContext(`
    refreshed=0;catchUp=async()=>{refreshed++};
    state.me={device:{id:'fixture'}};state.loggedOut=false;
  `, ctx);
  await vm.runInContext("handlePushUpdate({data:{type:'MESSAGES_CHANGED'}})", ctx);
  assert.equal(vm.runInContext("refreshed", ctx), 1);
  await vm.runInContext("handlePushUpdate({data:{type:'other'}})", ctx);
  vm.runInContext("state.loggedOut=true", ctx);
  await vm.runInContext("handlePushUpdate({data:{type:'MESSAGES_CHANGED'}})", ctx);
  assert.equal(vm.runInContext("refreshed", ctx), 1);
});
