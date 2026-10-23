import test from "node:test";
import assert from "node:assert/strict";
import {visibleGroups, analysisLabel, analysisGroupLabel} from "../app.js";

test("AI off exposes only complete inbox, rule OTP and unclassified other", () => {
  assert.deepEqual(visibleGroups(false), ["all", "otp", "other"]);
});
test("AI on keeps a dedicated pending location and separates spam from promotion", () => {
  assert.deepEqual(visibleGroups(true), ["all", "otp", "personal", "notification", "promotion", "spam", "pending"]);
});
test("OTP stays labelled as verification while AI is pending", () => {
  assert.equal(analysisGroupLabel({analysis:{otp:"482913",category:"",state:"pending"}}), "验证码");
  assert.equal(analysisGroupLabel({analysis:{otp:"",category:"otp",state:"complete"}}), "验证码");
  assert.equal(analysisGroupLabel({analysis:{otp:"",category:"promotion",state:"complete"}}), "推广");
  assert.equal(analysisGroupLabel({}), "待分析");
});
test("pending, processing and failed do not look classified", () => {
  assert.equal(analysisLabel(), "等待分析");
  assert.equal(analysisLabel({state:"processing"}), "正在分析");
  assert.equal(analysisLabel({state:"failed", error:"needs_review"}), "待确认分类");
  assert.equal(analysisLabel({state:"failed"}), "分析失败，可重试");
  assert.equal(analysisLabel({state:"complete", manual:true}), "手动分类");
});
