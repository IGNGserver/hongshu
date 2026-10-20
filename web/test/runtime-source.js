import { readFileSync } from "node:fs";

// The existing VM harness exercises private UI/session functions. Include the real local
// contact module rather than stubbing it, retaining all of the original session assertions.
export function runtimeSource() {
  return ["../local-contacts.js", "../app.js"].map(path =>
    readFileSync(new URL(path, import.meta.url), "utf8").replace(/^import .*;\n/gm, "").replace(/^export /gm, ""),
  ).join("\n");
}
