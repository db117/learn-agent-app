import type {FormEvent} from "react";
import type {LearnUnitResponse, SessionDetail, TutorEvent} from "../lib/api";

export type TutorPanelProps = {
    learnUnit: LearnUnitResponse | null;
    tutor: SessionDetail | null;
    tutorInput: string;
    events: TutorEvent[];
    activeTutorRunId: string | null;
    busy: boolean;
    onOpenTutor: () => void | Promise<void>;
    onTutorInputChange: (value: string) => void;
    onSendTutorMessage: (event: FormEvent<HTMLFormElement>) => void | Promise<void>;
    onCancelTutorRun: () => void | Promise<void>;
};

function eventLabel(event: TutorEvent) {
    switch (event.eventType) {
        case "skill_load_start":
            return `加载 Skill${event.skillName ? ` · ${event.skillName}` : ""}`;
        case "skill_load_complete":
            return `Skill 已加载${event.skillName ? ` · ${event.skillName}` : ""}`;
        case "reasoning_summary":
            return "处理中摘要";
        case "tool_call":
            return "工具调用";
        case "tool_result":
            return "工具结果";
        case "text_delta":
            return "回答";
        case "error":
            return "错误";
        case "complete":
            return "完成";
        case "cancelled":
            return "已取消";
    }
}

function eventText(event: TutorEvent) {
    if (event.eventType === "reasoning_summary") return event.summary || event.content || "处理中";
    if (event.eventType === "tool_call") return event.toolCall || event.content || "工具已调用";
    if (event.eventType === "tool_result") return event.toolResult || event.content || "工具已返回结果";
    return event.content || (event.eventType === "complete" ? "本次回答已完成" : "(empty)");
}

function isProgressEvent(event: TutorEvent) {
    return event.eventType === "skill_load_start" || event.eventType === "skill_load_complete" || event.eventType === "reasoning_summary";
}

export function TutorPanel({
                               learnUnit,
                               tutor,
                               tutorInput,
                               events,
                               activeTutorRunId,
                               busy,
                               onOpenTutor,
                               onTutorInputChange,
                               onSendTutorMessage,
                               onCancelTutorRun,
                           }: TutorPanelProps) {
    if (!learnUnit) return <aside className="tutor panel">
        <div className="panel-title">Tutor</div>
        <p className="empty">选择当前 LearnUnit 后，Tutor 会在这里出现。</p></aside>;
    return (
        <aside className="tutor panel">
            <div className="panel-title"><span>Tutor</span><span className="muted">{tutor ? "linked" : "ready"}</span>
            </div>
            {!tutor ? (
                <div className="tutor-empty">
                    <p>围绕当前 LearnUnit 提问。Tutor 会读取你的目标、掌握度和最近答题反馈。</p>
                    <button className="secondary wide" onClick={() => void onOpenTutor()} disabled={busy}>打开 Tutor
                    </button>
                </div>
            ) : (
                <>
                    <div className="messages">
                        {tutor.messages.map((message) => <article className={`message ${message.role}`}
                                                                  key={message.id}>
                            <span className="message-role">{message.role === "user" ? "你" : "Tutor"}</span>
                            <p>{message.content}</p>
                        </article>)}
                        {!tutor.messages.length && <p className="empty">问一个关于当前 LearnUnit 的问题。</p>}
                    </div>
                    <form className="composer" onSubmit={(event) => void onSendTutorMessage(event)}>
                        <textarea value={tutorInput} onChange={(event) => onTutorInputChange(event.target.value)}
                                  placeholder="例如：如何理解这个概念？" rows={3}/>
                        <button className="primary" type="submit" disabled={!tutorInput.trim()}>发送</button>
                        {activeTutorRunId && <button className="secondary" type="button"
                                                     onClick={() => void onCancelTutorRun()}>取消</button>}
                    </form>
                    <details className="events-details">
                        <summary>Agent events ({events.length})</summary>
                        {events.slice(-8).map((event) => isProgressEvent(event) ? (
                            <details className={`event event-progress ${event.eventType}`} key={event.id}
                                     open={event.eventType === "skill_load_start"}>
                                <summary><span>{eventLabel(event)}</span>{event.status && <small>{event.status}</small>}
                                </summary>
                                <p>{eventText(event)}</p>
                            </details>
                        ) : (
                            <div className={`event event-${event.eventType}`} key={event.id}>
                                <span>{eventLabel(event)}</span>
                                <p>{eventText(event)}</p>
                            </div>
                        ))}
                    </details>
                </>
            )}
        </aside>
    );
}
