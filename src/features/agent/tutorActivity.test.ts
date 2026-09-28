import {describe, expect, it} from "vitest";
import {beginTutorTrace, cancelTutorTrace, failTutorTransport, recordTutorEvent} from "./tutorActivity";

describe("Tutor 请求过程", () => {
    it("只记录安全活动和首个回答标记，失败保留定位编号", () => {
        let trace = beginTutorTrace("turn-123", 1000);
        trace = recordTutorEvent(trace, {type: "tool.started", text: "已开始工具 read_file"}, 1500);
        trace = recordTutorEvent(trace, {type: "message.delta", text: "私有回答正文"}, 2000);
        trace = recordTutorEvent(trace, {type: "message.delta", text: "更多正文"}, 2100);
        trace = recordTutorEvent(trace, {type: "turn.failed", errorCode: "MODEL_REQUEST_FAILED"}, 3000);

        expect(trace.turnId).toBe("turn-123");
        expect(trace.status).toBe("failed");
        expect(trace.errorCode).toBe("MODEL_REQUEST_FAILED");
        expect(trace.items.map((item) => item.text)).toEqual([
            "正在连接 TutorAgent", "已开始工具 read_file", "开始收到回答", "请求失败",
        ]);
        expect(trace.items[1].elapsedMs).toBe(500);
        expect(failTutorTransport(trace, 4000)).toBe(trace);

        const cancelled = cancelTutorTrace(beginTutorTrace("turn-cancel", 1000), 1800);
        expect(cancelled.status).toBe("cancelled");
        expect(cancelled.endedAt).toBe(1800);

        const failed = failTutorTransport(beginTutorTrace("turn-http-fail", 1000), 2000, "MODEL_REQUEST_FAILED");
        expect(failed.errorCode).toBe("MODEL_REQUEST_FAILED");
    });
});
