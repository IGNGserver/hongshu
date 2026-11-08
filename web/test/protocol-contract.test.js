import { test } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

// The hub, Web and Android must agree on the error-code vocabulary. The Go test proves
// the shared contract matches what the server can emit; this test proves the Web client
// never maps a code the hub cannot return, which is what silently happens after a rename.
const contractPath = fileURLToPath(new URL("../../protocol/error-codes.v1.json", import.meta.url));
const appPath = fileURLToPath(new URL("../app.js", import.meta.url));

async function loadContract() {
  const contract = JSON.parse(await readFile(contractPath, "utf8"));
  assert.equal(contract.version, 1, "unexpected shared contract version");
  assert.ok(Array.isArray(contract.codes) && contract.codes.length > 40, "contract is too small");
  assert.ok(Array.isArray(contract.dynamic_codes?.patterns), "contract must document dynamic codes");
  return contract;
}

function clientErrorKeys(source) {
  const block = /const errors = \{([\s\S]*?)\n\};/.exec(source);
  assert.ok(block, "app.js no longer defines the errors map");
  return [...block[1].matchAll(/^\s*([a-z][a-z_]*):/gm)].map((match) => match[1]);
}

test("every code the Web client maps exists in the shared hub contract", async () => {
  const contract = await loadContract();
  const known = new Set(contract.codes);
  const dynamic = contract.dynamic_codes.patterns.map((pattern) => new RegExp(`^${pattern}$`));
  const source = await readFile(appPath, "utf8");
  const mapped = clientErrorKeys(source);
  assert.ok(mapped.length >= 15, `suspiciously few mapped codes: ${mapped.length}`);
  const phantom = mapped.filter((code) => !known.has(code) && !dynamic.some((pattern) => pattern.test(code)));
  assert.deepEqual(phantom, [], "Web maps codes the hub can never return");
});

test("Web keeps a user-facing message for every code it handles specially", async () => {
  const source = await readFile(appPath, "utf8");
  const mapped = new Set(clientErrorKeys(source));
  // These drive dedicated branches, so losing the message would surface a raw code.
  for (const code of ["cursor_reset", "unauthorized", "invalid_password", "rate_limited"]) {
    assert.ok(mapped.has(code), `missing user-facing message for ${code}`);
  }
  assert.match(source, /err(?:or)?\.code === "cursor_reset"/, "cursor_reset recovery branch disappeared");
});
