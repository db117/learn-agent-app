import assert from "node:assert/strict";
import {mergeModelDeltas} from "../src/lib/journeyDraftEvents.ts";

const event = (sequence, eventType, content, runId = "run-1") => ({
  sequence,
  runId,
  author: eventType === "user_message" ? "用户" : "大模型",
  eventType,
  content,
  outline: null,
  journeyId: null,
  status: "GENERATING",
  timestamp: "2026-09-11T00:00:00Z",
});

const display = mergeModelDeltas([
  event(1, "run_started", "开始"),
  event(2, "model_delta", "Types"),
  event(3, "model_delta", "，"),
  event(4, "model_delta", "联合"),
  event(5, "user_message", "继续"),
  event(6, "model_delta", "done"),
]);

assert.equal(display.length, 4);
assert.deepEqual(display.map(({eventType, content}) => [eventType, content]), [
  ["run_started", "开始"],
  ["model_delta", "Types，联合"],
  ["user_message", "继续"],
  ["model_delta", "done"],
]);
console.log("journey draft delta check passed");
