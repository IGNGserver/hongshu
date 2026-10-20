// Device-only presentation data. This module has no fetch, API or service-worker dependencies.
export function contactNumber(value) {
  const compact = String(value || "").replace(/[\s()\-]/g, "");
  if (!/^\+?[0-9]{7,20}$/.test(compact)) return null;
  const national = compact.replace(/^(?:\+86|0086)/, "");
  if (/^\d{3,6}$/.test(national) || /^106\d+$/.test(national)) return null;
  return /^1[3-9][0-9]{9}$/.test(national) ? national : compact;
}

const MAX_CONTACTS = 1000;
const MAX_PHOTO = 256 * 1024;
const MAX_PHOTOS = 8 * 1024 * 1024;
export function safeContactPhoto(value) {
  return value instanceof Blob && ["image/png", "image/jpeg", "image/webp"].includes(value.type) && value.size > 0 && value.size <= MAX_PHOTO ? value : null;
}

export function selectedContacts(selected) {
  if (!Array.isArray(selected) || selected.length > MAX_CONTACTS) throw new Error("每次最多选择 1000 个联系人");
  const records = new Map();
  for (const contact of selected) {
    const name = String(contact.name?.[0] || "").trim().slice(0, 200);
    if (!name) continue;
    const photo = safeContactPhoto(contact.icon?.[0]);
    // A contact can have several representations of the same number, but two selected
    // contacts sharing a number are ambiguous even if their names happen to be identical.
    for (const phone of new Set((contact.tel || []).map(contactNumber).filter(Boolean))) {
      records.set(phone, records.has(phone) ? { phone, ambiguous: true } : { phone, name, photo });
    }
  }
  return [...records.values()];
}

export function mergeSelectedContacts(previous, incoming) {
  const records = new Map(previous.map(record => [record.phone, record]));
  for (const record of incoming) records.set(record.phone, record);
  if (records.size > MAX_CONTACTS) throw new Error("本机最多保存 1000 个号码，请先清除不再需要的联系人");
  let bytes = 0;
  return [...records.values()].map(record => {
    const photo = safeContactPhoto(record.photo);
    bytes += photo?.size || 0;
    return record.ambiguous ? { phone: record.phone, ambiguous: true } :
      { phone: record.phone, name: record.name, photo: bytes <= MAX_PHOTOS ? photo : null };
  });
}

export async function contactPickerProperties(nav, secure, topLevel) {
  if (!secure || !topLevel || typeof nav?.contacts?.select !== "function" || typeof nav.contacts.getProperties !== "function") return [];
  try {
    const supported = await nav.contacts.getProperties();
    if (!supported.includes("name") || !supported.includes("tel")) return [];
    return ["name", "tel", ...(supported.includes("icon") ? ["icon"] : [])];
  } catch { return []; }
}

export class ContactStorage {
  constructor(factory = globalThis.indexedDB) { this.factory = factory; }
  async transact(mode, action, transform = value => value) {
    if (!this.factory) throw new Error("当前浏览器无法保存本机联系人");
    const db = await new Promise((resolve, reject) => {
      let abandoned = false;
      const request = this.factory.open("hongshu-local-contacts", 1);
      request.onupgradeneeded = () => request.result.createObjectStore("settings");
      request.onerror = () => reject(new Error("无法打开本机联系人存储"));
      request.onblocked = () => { abandoned = true; reject(new Error("本机联系人存储被其他标签页占用，请关闭旧标签页后重试")); };
      request.onsuccess = () => {
        if (abandoned) request.result.close();
        else { request.result.onversionchange = () => request.result.close(); resolve(request.result); }
      };
    });
    try {
      return await new Promise((resolve, reject) => {
        const tx = db.transaction("settings", mode);
        let result;
        const request = action(tx.objectStore("settings"));
        request.onsuccess = () => { result = request.result; };
        tx.oncomplete = () => resolve(transform(result));
        tx.onerror = tx.onabort = () => reject(new Error("本机联系人读写失败；可能是存储空间不足或其他标签页已更新，请刷新后重试"));
      });
    } finally { db.close(); }
  }
  read() {
    let generation = 0;
    return this.transact("readonly", store => {
      const version = store.get("generation");
      version.onsuccess = () => { generation = version.result || 0; };
      return store.get("contacts");
    }, result => ({ ...result, generation }));
  }
  write(value, expectedGeneration) {
    return this.transact("readwrite", store => {
      const version = store.get("generation");
      version.addEventListener("success", () => {
        if ((version.result || 0) !== expectedGeneration) { store.transaction.abort(); return; }
        try {
          store.put((version.result || 0) + 1, "generation");
          store.put(value, "contacts");
        } catch { store.transaction.abort(); }
      });
      return version;
    }, previous => (previous || 0) + 1);
  }
  clear() {
    return this.transact("readwrite", store => {
      const version = store.get("generation");
      version.addEventListener("success", () => {
        try {
          store.put((version.result || 0) + 1, "generation");
          store.delete("contacts");
        } catch { store.transaction.abort(); }
      });
      return version;
    }, previous => (previous || 0) + 1);
  }
}

export class BrowserContacts {
  constructor(storage = new ContactStorage(), urls = globalThis.URL) {
    this.storage = storage;
    this.urls = urls;
    this.records = [];
    this.enabled = false;
    this.owner = "";
    this.version = 0;
    this.generation = 0;
    this.photos = new Map();
    this.queue = Promise.resolve();
  }
  serialize(task) {
    const result = this.queue.then(task);
    this.queue = result.catch(() => {});
    return result;
  }
  release() {
    for (const url of this.photos.values()) this.urls.revokeObjectURL(url);
    this.photos.clear();
  }
  reset() {
    this.version++;
    this.release();
    this.enabled = false;
    this.records = [];
  }
  async load(owner) {
    const version = ++this.version;
    this.release();
    this.enabled = false;
    this.records = [];
    this.owner = owner;
    return this.serialize(async () => {
      const saved = await this.storage.read();
      if (version !== this.version || owner !== this.owner) return;
      this.generation = saved?.generation || 0;
      if (!saved || saved.owner !== owner || !Array.isArray(saved.records)) {
        this.version++;
        return;
      }
      // Validate disk data as carefully as newly picked data; never use arbitrary URLs.
      const records = saved.records.filter(r => r && contactNumber(r.phone) === r.phone &&
        (r.ambiguous === true || (typeof r.name === "string" && r.name.trim() && r.name.length <= 200)));
      this.records = mergeSelectedContacts([], records.slice(0, MAX_CONTACTS));
      this.enabled = saved.enabled === true;
      this.version++;
    });
  }
  save(records, enabled) {
    const owner = this.owner;
    const version = this.version;
    const generation = this.generation;
    return this.serialize(async () => {
      if (owner !== this.owner || version !== this.version) return false;
      const nextGeneration = await this.storage.write({ owner, records, enabled }, generation);
      if (owner !== this.owner || version !== this.version) return false;
      this.release();
      this.records = records;
      this.enabled = enabled;
      this.generation = nextGeneration ?? generation + 1;
      this.version++;
      return true;
    });
  }
  select(selected) {
    return this.save(mergeSelectedContacts(this.records, selectedContacts(selected)), true);
  }
  toggle(enabled) { return this.save(this.records, enabled); }
  clear() {
    this.reset(); // Hide immediately, including when persistent deletion fails.
    return this.serialize(async () => { this.generation = (await this.storage.clear()) ?? this.generation + 1; });
  }
  find(sender) {
    if (!this.enabled) return null;
    const phone = contactNumber(sender);
    const record = this.records.find(r => r.phone === phone);
    return record && !record.ambiguous ? record : null;
  }
  photo(sender) {
    const record = this.find(sender);
    if (!safeContactPhoto(record?.photo)) return null;
    if (!this.photos.has(record.phone)) this.photos.set(record.phone, this.urls.createObjectURL(record.photo));
    return this.photos.get(record.phone);
  }
  search(query) {
    if (!this.enabled) return [];
    const q = query.trim().toLocaleLowerCase();
    return this.records.filter(r => !r.ambiguous && (!q || r.name.toLocaleLowerCase().includes(q) || r.phone.includes(q)));
  }
}
