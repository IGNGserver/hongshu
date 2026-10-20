import test from "node:test";
import assert from "node:assert/strict";
import vm from "node:vm";
import { BrowserContacts, ContactStorage, contactNumber, selectedContacts, mergeSelectedContacts, safeContactPhoto, contactPickerProperties } from "../local-contacts.js";
import { runtimeSource } from "./runtime-source.js";

const selected = (name = "合成朋友", phone = "+86 138-0000-0001", photo) => [{ name: [name], tel: [phone], icon: photo ? [photo] : [] }];
function fixture() {
  let saved;
  const storage = { read: async () => saved, write: async value => { saved = structuredClone(value); }, clear: async () => { saved = undefined; } };
  const created = [], revoked = [];
  const source = new BrowserContacts(storage, { createObjectURL: blob => { created.push(blob); return `blob:synthetic-${created.length}`; }, revokeObjectURL: url => revoked.push(url) });
  return { source, storage, created, revoked, saved: () => saved };
}

test("local number matching is exact, country-aware only for Chinese mobiles, never by suffix", () => {
  assert.equal(contactNumber("+86 (138) 0000-0001"), "13800000001");
  assert.equal(contactNumber("008613800000001"), "13800000001");
  assert.equal(contactNumber("+1 202-555-0123"), "+12025550123");
  assert.notEqual(contactNumber("+12025550123"), contactNumber("2025550123"));
  for (const value of ["10086", "95588", "+8610086", "1069012345678", "Alice", "13800000001x", ""]) assert.equal(contactNumber(value), null);
});

test("picker capability requires secure top-level name/tel and gracefully degrades without icon", async () => {
  const nav = { contacts: { select() {}, getProperties: async () => ["name", "tel"] } };
  assert.deepEqual(await contactPickerProperties(nav, true, true), ["name", "tel"]);
  assert.deepEqual(await contactPickerProperties(nav, false, true), []);
  assert.deepEqual(await contactPickerProperties(nav, true, false), []);
  assert.deepEqual(await contactPickerProperties({}, true, true), []);
  nav.contacts.getProperties = async () => ["name", "tel", "icon", "email"];
  assert.deepEqual(await contactPickerProperties(nav, true, true), ["name", "tel", "icon"]);
  nav.contacts.getProperties = async () => ["name"];
  assert.deepEqual(await contactPickerProperties(nav, true, true), []);
  nav.contacts.getProperties = async () => { throw Error("unsupported"); };
  assert.deepEqual(await contactPickerProperties(nav, true, true), []);
});

test("selection handles multiple phones, ambiguity, empty names and bounded safe photo blobs", () => {
  const photo = new Blob(["synthetic"], { type: "image/png" });
  const records = selectedContacts([{ name: ["合成"], tel: ["13800000001", "+8613800000001", "13900000001"], icon: [photo] }]);
  assert.equal(records.length, 2);
  assert.equal(records[0].photo, photo);
  const duplicate = selectedContacts([...selected(), ...selected("合成另一人")]);
  assert.deepEqual(duplicate, [{ phone: "13800000001", ambiguous: true }]);
  assert.deepEqual(selectedContacts(selected(" ")), []);
  assert.deepEqual(selectedContacts(selected("合成", "10086")), []);
  for (const unsafe of ["https://example.invalid/avatar", new Blob(["svg"], { type: "image/svg+xml" }), new Blob([new Uint8Array(256 * 1024 + 1)], { type: "image/png" }), new Blob([])]) assert.equal(safeContactPhoto(unsafe), null);
  assert.throws(() => selectedContacts(Array(1001).fill({})), /1000/);
  const many = Array.from({ length: 1001 }, (_, i) => ({ phone: String(13800000000 + i), name: "合成" }));
  assert.throws(() => mergeSelectedContacts([], many), /1000/);
});

test("local data survives reload for this browser identity only; disable and clear revoke photos", async () => {
  const f = fixture();
  await f.source.load("browser-A");
  assert.equal(f.source.enabled, false);
  await f.source.select(selected("合成朋友", "13800000001", new Blob(["synthetic"], { type: "image/png" })));
  assert.equal(f.source.find("+8613800000001").name, "合成朋友");
  assert.equal(f.source.find("13900000001"), null);
  assert.equal(f.source.search("朋友").length, 1);
  assert.equal(f.source.search("不存在").length, 0);
  assert.equal(f.source.photo("13800000001"), "blob:synthetic-1");
  assert.equal(f.source.photo("13800000001"), "blob:synthetic-1");
  assert.equal(f.created.length, 1);
  await f.source.toggle(false);
  assert.equal(f.source.find("13800000001"), null);
  assert.deepEqual(f.source.search("朋友"), []);
  assert.deepEqual(f.revoked, ["blob:synthetic-1"]);
  await f.source.toggle(true);
  await f.source.load("browser-A");
  assert.equal(f.source.find("13800000001").name, "合成朋友");
  await f.source.load("browser-B");
  assert.equal(f.source.enabled, false);
  await f.source.load("browser-A");
  await f.source.clear();
  assert.equal(f.source.find("13800000001"), null);
  assert.equal(f.saved(), undefined);
});

test("reselect updates a name/photo; corrupt disk URLs and ambiguous contacts cannot become avatars", async () => {
  const f = fixture();
  await f.source.load("A");
  await f.source.select(selected());
  await f.source.select(selected("合成新名称"));
  assert.equal(f.source.find("13800000001").name, "合成新名称");
  await f.source.select([...selected(), ...selected("合成重名")]);
  assert.equal(f.source.find("13800000001"), null);
  assert.equal(f.source.photo("13800000001"), null);
  await f.storage.write({ owner: "A", enabled: true, records: [{ phone: "13800000001", name: "<img onerror=evil>", photo: "https://evil.invalid" }, { phone: "not a phone", name: "invalid" }] });
  await f.source.load("A");
  assert.equal(f.source.records.length, 1);
  assert.equal(f.source.photo("13800000001"), null);
});

test("storage failures never claim a saved selection, deletion hides memory even on disk error", async () => {
  const f = fixture();
  await f.source.load("A");
  f.storage.write = async () => { throw Error("quota"); };
  await assert.rejects(f.source.select(selected()), /quota/);
  assert.equal(f.source.enabled, false);
  f.storage.clear = async () => { throw Error("blocked"); };
  await assert.rejects(f.source.clear(), /blocked/);
  assert.equal(f.source.records.length, 0);
  await assert.rejects(new ContactStorage(null).read(), /无法保存/);
});

test("clear, logout reset and newer reload fence late reads/writes", async () => {
  const f = fixture();
  let release;
  f.storage.read = () => new Promise(resolve => { release = resolve; });
  const pending = f.source.load("A");
  await Promise.resolve();
  f.source.reset();
  release({ owner: "A", enabled: true, records: selectedContacts(selected()) });
  await pending;
  assert.equal(f.source.enabled, false);
  f.storage.write = () => new Promise(resolve => { release = resolve; });
  const writing = f.source.select(selected());
  await Promise.resolve();
  const clearing = f.source.clear();
  release();
  await Promise.all([writing, clearing]);
  assert.equal(f.source.enabled, false);
  assert.equal(f.source.records.length, 0);
});

test("UI resolves local names before hub aliases without modifying messages or network arguments", async () => {
  const ctx = vm.createContext({ URLSearchParams, Uint8Array, AbortController, DOMException, console, Blob, URL });
  vm.runInContext(runtimeSource(), ctx);
  vm.runInContext(`document={querySelector:()=>null};browserContacts.owner='A';browserContacts.enabled=true;
    browserContacts.records=[{phone:'13800000001',name:'合成私密姓名'}];state.contacts={'13800000001':'hub alias'};
    state.me={device:{id:'A'}};calls=[];
    api=async path=>{calls.push(path);return {conversations:[{sender:'13800000001',body:'synthetic'}]}};
    openContactConversation=async sender=>{opened=sender};`, ctx);
  assert.equal(vm.runInContext("displayContact('13800000001')", ctx), "合成私密姓名");
  await vm.runInContext("openLocalContact('13800000001')", ctx);
  assert.equal(vm.runInContext("opened", ctx), "13800000001");
  assert.equal(vm.runInContext("calls.length", ctx), 1);
  assert.equal(vm.runInContext("calls.some(path=>path.includes('13800000001')||path.includes('合成私密姓名'))", ctx), false);
  assert.equal(vm.runInContext("state.q", ctx), "");
});

test("a delayed local contact lookup cannot override a newer search or settings navigation", async () => {
  for (const change of ["state.page='settings'", "contactSearchRequest++", "browserContacts.reset()"] ) {
    const ctx = vm.createContext({ URLSearchParams, Uint8Array, AbortController, DOMException, console, Blob, URL });
    vm.runInContext(runtimeSource(), ctx);
    vm.runInContext(`browserContacts.enabled=true;browserContacts.records=[{phone:'13800000001',name:'合成'}];
      state.me={device:{id:'A'}};opened=false;api=()=>new Promise(resolve=>release=resolve);
      openContactConversation=async()=>{opened=true};pending=openLocalContact('13800000001');`, ctx);
    vm.runInContext(change + ";release({conversations:[{sender:'13800000001'}]})", ctx);
    await vm.runInContext("pending", ctx);
    assert.equal(vm.runInContext("opened", ctx), false, change);
  }
});
