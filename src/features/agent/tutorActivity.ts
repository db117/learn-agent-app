export type TutorEvent = { type: string; text?: string; errorCode?: string; turnId?: string };
export type TutorActivityItem = { type: string; elapsedMs: number; text: string };
export type TutorTrace = {
    turnId: string;
    startedAt: number;
    endedAt?: number;
    status: "running" | "completed" | "failed" | "cancelled";
    errorCode?: string;
    items: TutorActivityItem[];
};

export function beginTutorTrace(turnId: string, startedAt: number): TutorTrace {
    return {
        turnId,
        startedAt,
        status: "running",
        items: [{type: "client.started", elapsedMs: 0, text: "正在连接 TutorAgent"}],
    };
}

export function recordTutorEvent(trace: TutorTrace, event: TutorEvent, at: number): TutorTrace {
    if (trace.status !== "running") return trace;
    let text: string | undefined;
    let status: TutorTrace["status"] = trace.status;
    switch (event.type) {
        case "turn.started":
            text = "TutorAgent 已开始处理";
            break;
        case "activity":
        case "skill.loaded":
        case "tool.started":
        case "tool.completed":
        case "tool.failed":
        case "workspace.changed":
            text = event.text;
            break;
        case "message.delta":
            if (!trace.items.some((item) => item.type === "message.delta")) text = "开始收到回答";
            break;
        case "turn.completed":
            text = "回答完成";
            status = "completed";
            break;
        case "turn.failed":
            text = "请求失败";
            status = "failed";
            break;
        case "turn.cancelled":
            text = "请求已取消";
            status = "cancelled";
            break;
        default:
            return trace;
    }
    return {
        ...trace,
        status,
        endedAt: status === "running" ? undefined : at,
        errorCode: event.type === "turn.failed" ? event.errorCode : trace.errorCode,
        items: text ? [...trace.items, {
            type: event.type,
            elapsedMs: Math.max(0, at - trace.startedAt),
            text
        }] : trace.items,
    };
}

export function failTutorTransport(trace: TutorTrace, at: number, errorCode?: string): TutorTrace {
    if (trace.status !== "running") return trace;
    return {
        ...trace,
        status: "failed",
        endedAt: at,
        errorCode,
        items: [...trace.items, {
            type: "client.failed",
            elapsedMs: Math.max(0, at - trace.startedAt),
            text: "连接或读取响应失败"
        }],
    };
}

export function cancelTutorTrace(trace: TutorTrace, at: number): TutorTrace {
    return recordTutorEvent(trace, {type: "turn.cancelled"}, at);
}
