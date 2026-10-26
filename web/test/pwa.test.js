import test from "node:test";
import assert from "node:assert/strict";
import { viewportMetrics, installMobileViewport } from "../app.js";

test("keyboard viewport protects the visible area and leaves pinch zoom alone", () => {
  assert.deepEqual(viewportMetrics({ height: 420, offsetTop: 30, scale: 1 }, 820, true), { height: 420, top: 30, keyboard: true });
  assert.deepEqual(viewportMetrics({ height: 820, offsetTop: -4, scale: 1 }, 820, false), { height: 820, top: 0, keyboard: false });
  assert.equal(viewportMetrics({ height: 400, offsetTop: 50, scale: 2 }, 820, true), null);
});

test("viewport installation responds to keyboard/focus events and releases listeners", () => {
  const handlers = new Map();
  const target = name => ({ addEventListener: (event, fn) => handlers.set(`${name}:${event}`, fn), removeEventListener: event => handlers.delete(`${name}:${event}`) });
  const styles = new Map();
  const classes = new Map();
  const viewport = { ...target("viewport"), height: 700, offsetTop: 0, scale: 1 };
  const win = { ...target("window"), visualViewport: viewport, innerHeight: 700, requestAnimationFrame: fn => fn() };
  const doc = { ...target("document"), activeElement: { matches: () => true }, documentElement: { style: { setProperty: (key, value) => styles.set(key, value) }, classList: { toggle: (key, value) => classes.set(key, value) } } };
  const dispose = installMobileViewport(win, doc);
  viewport.height = 380;
  handlers.get("viewport:resize")();
  assert.equal(styles.get("--hs-viewport-height"), "380px");
  assert.equal(classes.get("keyboard-visible"), true);
  viewport.scale = 2;
  viewport.height = 200;
  handlers.get("viewport:resize")();
  assert.equal(styles.get("--hs-viewport-height"), "380px");
  dispose();
  assert.equal(handlers.size, 0);
});