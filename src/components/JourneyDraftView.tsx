import {useState} from "react";
import type {JourneyDraftEvent, JourneyDraftOutline} from "../lib/api";

type JourneyDraftViewProps = {
    events: JourneyDraftEvent[];
    outline: JourneyDraftOutline | null;
    status: string;
    busy: boolean;
    onSendGuidance: (content: string) => void | Promise<void>;
    onConfirm: () => void | Promise<void>;
    onCancel: () => void | Promise<void>;
};

function eventLabel(event: JourneyDraftEvent) {
    if (event.eventType === "model_delta") return "大模型";
    if (event.eventType === "user_message") return "你";
    return event.author;
}

function statusLabel(status: string) {
    switch (status) {
        case "GENERATING":
            return "生成中";
        case "WAITING_CONFIRMATION":
            return "等待确认";
        case "CONFIRMING":
            return "保存中";
        case "CONFIRMED":
            return "已保存";
        case "FAILED":
            return "生成失败";
        case "CANCELLED":
            return "已取消";
        default:
            return status;
    }
}

function Outline({outline}: {outline: JourneyDraftOutline}) {
    return (
        <div className="draft-outline-content">
            <p className="muted">{outline.languages.map((language) => language.name).join("、")}</p>
            {outline.learnUnits.map((unit) => (
                <article className="draft-unit" key={unit.code}>
                    <div className="draft-unit-title"><span>{unit.sequence}</span><strong>{unit.name}</strong></div>
                    <p>{unit.description}</p>
                    <h4>学习目标</h4>
                    <ul>{unit.learningObjectives.map((item) => <li key={item}>{item}</li>)}</ul>
                    <h4>关键概念</h4>
                    <div className="tag-list">{unit.keyConcepts.map((item) => <span key={item}>{item}</span>)}</div>
                </article>
            ))}
        </div>
    );
}

export function JourneyDraftView({
                                    events,
                                    outline,
                                    status,
                                    busy,
                                    onSendGuidance,
                                    onConfirm,
                                    onCancel,
                                }: JourneyDraftViewProps) {
    const [guidance, setGuidance] = useState("");
    const canConfirm = status === "WAITING_CONFIRMATION" && outline !== null;
    const terminal = status === "FAILED" || status === "CANCELLED" || status === "CONFIRMED";

    async function sendGuidance() {
        const content = guidance.trim();
        if (!content) return;
        await onSendGuidance(content);
        setGuidance("");
    }

    return (
        <section className="journey-draft panel">
            <div className="draft-header">
                <div>
                    <div className="section-kicker">AGENT · JOURNEY OUTLINE</div>
                    <h2>先一起确认知识点和学习路径</h2>
                    <p className="lead">现在只生成大纲。详细教学内容和练习题会在你开始学习对应单元时生成。</p>
                </div>
                <span className="status-pill">{statusLabel(status)}</span>
            </div>
            <div className="draft-grid">
                <section className="draft-conversation" aria-live="polite">
                    <div className="panel-title">Agent / 大模型对话</div>
                    <div className="draft-events">
                        {events.map((event) => (
                            <article className={`draft-event draft-event-${event.author === "用户" ? "user" : "agent"}`} key={event.sequence}>
                                <div><span>{eventLabel(event)}</span><small>{new Date(event.timestamp).toLocaleTimeString()}</small></div>
                                <p>{event.content}</p>
                            </article>
                        ))}
                        {!events.length && <p className="empty">正在连接 Agent…</p>}
                    </div>
                    <div className="draft-composer">
                        <textarea value={guidance}
                                  onChange={(event) => setGuidance(event.target.value)}
                                  placeholder="可以告诉 Agent：删掉哪些知识点、增加哪些内容，或调整学习顺序。"
                                  rows={3} disabled={terminal}/>
                        <button className="secondary" type="button" onClick={() => void sendGuidance()}
                                disabled={busy || terminal || !guidance.trim()}>让 Agent 调整</button>
                    </div>
                </section>
                <aside className="draft-outline panel">
                    <div className="panel-title"><span>知识点和路径</span><span className="muted">{outline?.learnUnits.length ?? 0} 个单元</span></div>
                    {outline ? <Outline outline={outline}/> : <p className="empty">大纲生成后会显示在这里。</p>}
                </aside>
            </div>
            <div className="button-row draft-actions">
                <button className="primary" type="button" onClick={() => void onConfirm()} disabled={busy || !canConfirm}>
                    {status === "CONFIRMING" ? "保存中…" : "确认知识点和路径并开始学习"}
                </button>
                <button className="secondary" type="button" onClick={() => void onCancel()} disabled={busy || status === "CONFIRMED"}>
                    {status === "FAILED" || status === "CANCELLED" ? "返回重新填写" : "取消"}
                </button>
                {!canConfirm && !terminal && <span className="muted">可在生成过程中继续补充要求</span>}
            </div>
        </section>
    );
}
