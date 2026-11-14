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

test("OTP push attaches one-tap copy action, sets unread badge, and preserves MESSAGES_CHANGED contract", async () => {
  const messages = [];
  const runtime = workerRuntime(pushWorker, new Map(), undefined, { clients: [
    { postMessage: message => messages.push(message) },
  ] });
  await runtime.push({ json: () => ({
    title: "验证码 482913 · 106900",
    body: "您的验证码为482913，5分钟内有效",
    tag: "hongshu-otp-42",
    url: "/#sender=106900&otp=482913&msg=42",
    otp: "482913",
    sender: "106900",
    message_id: 42,
    unread_count: 3,
    renotify: true,
  }) });
  assert.equal(runtime.notifications.length, 1);
  assert.deepEqual(JSON.parse(JSON.stringify(runtime.notifications[0].options.actions)), [{ action: "copy", title: "复制 482913" }]);
  assert.equal(runtime.notifications[0].options.renotify, true);
  assert.equal(runtime.notifications[0].options.data.otp, "482913");
  assert.equal(runtime.notifications[0].options.data.sender, "106900");
  assert.equal(runtime.notifications[0].options.data.messageId, 42);
  assert.equal(runtime.badge, 3);
  assert.equal(messages.length, 1);
  assert.deepEqual(Object.keys(messages[0]), ["type"]);
  assert.equal(messages[0].type, "MESSAGES_CHANGED");
  assert.equal(runtime.fetches, 0);
});

test("notification click focuses an already-mounted root window via NOTIFICATION_OPEN without navigate reload", async () => {
  const calls = [];
  const client = {
    url: "https://example.test/",
    postMessage: msg => calls.push(["postMessage", JSON.parse(JSON.stringify(msg))]),
    navigate: async url => { calls.push(["navigate", url]); return client; },
    focus: async () => { calls.push(["focus"]); },
  };
  const runtime = workerRuntime(pushWorker, new Map(), undefined, { clients: [client] });
  await runtime.click({
    body: "您的验证码为482913",
    data: {
      url: "/#sender=106900&otp=482913&msg=42",
      otp: "482913",
      sender: "106900",
      messageId: 42,
      body: "您的验证码为482913",
    },
    close() {},
  }, "copy");
  assert.equal(calls.some(([kind]) => kind === "navigate"), false);
  assert.deepEqual(calls, [
    ["postMessage", {
      type: "NOTIFICATION_OPEN",
      url: "/#sender=106900&otp=482913&msg=42",
      action: "copy",
      otp: "482913",
      sender: "106900",
      messageId: 42,
      body: "您的验证码为482913",
    }],
    ["focus"],
  ]);
  assert.equal(runtime.opened, null);
});

test("NOTIFICATION_OPEN populates in-memory OTP capsule banner on signed-in session and refreshes once", async () => {
  const ctx = vm.createContext({ URLSearchParams, Uint8Array, AbortController, DOMException, console, atob });
  vm.runInContext(runtimeSource(), ctx);
  vm.runInContext(`
    refreshed=0;catchUp=async()=>{refreshed++};
    state.me={device:{id:'fixture'}};state.loggedOut=false;
  `, ctx);
  await vm.runInContext(`handlePushUpdate({data:{
    type:'NOTIFICATION_OPEN',
    url:'/#sender=106900&otp=482913&msg=42',
    otp:'482913',
    sender:'106900',
    messageId:42,
    body:'验证码482913'
  }})`, ctx);
  assert.equal(vm.runInContext("state.otpBanner?.otp", ctx), "482913");
  assert.equal(vm.runInContext("state.otpBanner?.sender", ctx), "106900");
  assert.equal(vm.runInContext("state.sender", ctx), "106900");
  assert.equal(vm.runInContext("refreshed", ctx), 1);

  vm.runInContext("state.loggedOut=true;state.otpBanner=null", ctx);
  await vm.runInContext("handlePushUpdate({data:{type:'NOTIFICATION_OPEN',otp:'999999',sender:'10086'}})", ctx);
  assert.equal(vm.runInContext("state.otpBanner", ctx), null);
  assert.equal(vm.runInContext("refreshed", ctx), 1);
});
