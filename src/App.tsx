import {useEffect, useRef, useState} from "react";

const BACKEND_URL = "http://127.0.0.1:18080";

type Health = Record<string, unknown>;
type TutorMessage = { role: "user" | "assistant"; text: string; timestamp?: string };
type TutorEvent = { type: string; text?: string; errorCode?: string };
type TutorError = { code?: string; message?: string };

function formatValue(value: unknown) {
    if (value === undefined || value === null) return "—";
    return typeof value === "string" ? value : JSON.stringify(value);
}

async function readError(response: Response) {
    try {
        const error = await response.json() as TutorError;
        return error.message ?? `请求失败（${response.status}）`;
    } catch {
        return `请求失败（${response.status}）`;
    }
}

async function readSse(response: Response, onEvent: (event: TutorEvent) => void) {
    if (!response.body) throw new Error("Tutor 流没有返回内容");
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let dataLines: string[] = [];

    const emit = () => {
        if (dataLines.length === 0) return;
        const data = dataLines.join("\n");
        dataLines = [];
        onEvent(JSON.parse(data) as TutorEvent);
    };

    while (true) {
        const chunk = await reader.read();
        buffer += decoder.decode(chunk.value ?? new Uint8Array(), {stream: !chunk.done});
        const lines = buffer.split("\n");
        buffer = lines.pop() ?? "";
        for (const rawLine of lines) {
            const line = rawLine.endsWith("\r") ? rawLine.slice(0, -1) : rawLine;
            if (line === "") {
                emit();
            } else if (line.startsWith("data:")) {
                dataLines.push(line.slice(5).trimStart());
            }
        }
        if (chunk.done) {
            if (buffer !== "" && buffer.startsWith("data:")) dataLines.push(buffer.slice(5).trimStart());
            emit();
            return;
        }
    }
}

export default function App() {
    const [health, setHealth] = useState<Health | null>(null);
    const [healthState, setHealthState] = useState<"loading" | "online" | "error">("loading");
    const [learnerId, setLearnerId] = useState("1");
    const [journeyId, setJourneyId] = useState("1");
    const [sessionId, setSessionId] = useState<string | null>(null);
    const [currentLearnUnit, setCurrentLearnUnit] = useState<string | null>(null);
    const [messages, setMessages] = useState<TutorMessage[]>([]);
    const [draft, setDraft] = useState("");
    const [activity, setActivity] = useState("等待创建 Tutor Session");
    const [error, setError] = useState<string | null>(null);
    const [loadingSession, setLoadingSession] = useState(false);
    const [sending, setSending] = useState(false);
    const streamController = useRef<AbortController | null>(null);

    const removePendingAssistant = () => {
        setMessages((current) => current.at(-1)?.role === "assistant" ? current.slice(0, -1) : current);
    };

    useEffect(() => {
        const controller = new AbortController();
        fetch(`${BACKEND_URL}/health`, {signal: controller.signal})
            .then((response) => {
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                return response.json() as Promise<Health>;
            })
            .then((payload) => {
                setHealth(payload);
                setHealthState("online");
            })
            .catch((requestError: Error) => {
                if (requestError.name !== "AbortError") setHealthState("error");
            });
        return () => controller.abort();
    }, []);

    const createSession = async () => {
        setError(null);
        setLoadingSession(true);
        try {
            const response = await fetch(`${BACKEND_URL}/api/tutor/sessions`, {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({learnerId: Number(learnerId), journeyId: Number(journeyId)}),
            });
            if (!response.ok) throw new Error(await readError(response));
            const session = await response.json() as {
                sessionId: string;
                restored: boolean;
                currentLearnUnitCode: string;
                messages: TutorMessage[];
            };
            setSessionId(session.sessionId);
            setCurrentLearnUnit(session.currentLearnUnitCode);
            setMessages(session.messages);
            setActivity(session.restored ? "已恢复 Tutor Session" : "Tutor Session 已创建");
        } catch (requestError) {
            setError(requestError instanceof Error ? requestError.message : "无法创建 Tutor Session");
        } finally {
            setLoadingSession(false);
        }
    };

    const sendMessage = async () => {
        const text = draft.trim();
        if (!sessionId || !text || sending) return;
        setError(null);
        setDraft("");
        setMessages((current) => [...current, {role: "user", text}, {role: "assistant", text: ""}]);
        setSending(true);
        setActivity("正在连接 TutorAgent");
        const controller = new AbortController();
        streamController.current = controller;
        try {
            const response = await fetch(`${BACKEND_URL}/api/tutor/sessions/${sessionId}/messages`, {
                method: "POST",
                headers: {"Content-Type": "application/json", Accept: "text/event-stream"},
                body: JSON.stringify({turnId: crypto.randomUUID(), text}),
                signal: controller.signal,
            });
            if (!response.ok) throw new Error(await readError(response));
            await readSse(response, (event) => {
                if (event.type === "activity") setActivity(event.text ?? "TutorAgent 正在工作");
                if (event.type === "message.delta" && event.text) {
                    setMessages((current) => {
                        const next = [...current];
                        const last = next.length - 1;
                        if (last >= 0 && next[last].role === "assistant") {
                            next[last] = {...next[last], text: next[last].text + event.text};
                        }
                        return next;
                    });
                }
                if (event.type === "turn.completed") setActivity("TutorAgent 已完成回答");
                if (event.type === "turn.cancelled") {
                    removePendingAssistant();
                    setActivity("Tutor Turn 已取消");
                }
                if (event.type === "turn.failed") {
                    removePendingAssistant();
                    setError(event.errorCode ?? "TutorAgent 暂时不可用");
                }
            });
        } catch (requestError) {
            if (requestError instanceof Error && requestError.name !== "AbortError") {
                removePendingAssistant();
                setError(requestError.message);
            }
        } finally {
            streamController.current = null;
            setSending(false);
        }
    };

    const cancelMessage = async () => {
        if (!sessionId || !sending) return;
        try {
            await fetch(`${BACKEND_URL}/api/tutor/sessions/${sessionId}/cancel`, {method: "POST"});
        } finally {
            streamController.current?.abort();
            removePendingAssistant();
            setActivity("Tutor Turn 已取消");
        }
    };

    return (
        <main className="app-shell">
            <section className="hero">
                <p className="eyebrow">LEARN AGENT V2 · TUTOR RUNTIME</p>
                <h1>和 TutorAgent 一起学习。</h1>
                <p className="intro">发送一段文本，观察安全的活动状态和回答增量实时返回。</p>
            </section>

            <section className="status-grid" aria-label="Runtime status">
                <article className="status-card compact-card">
                    <div className="card-heading">
                        <span className={`status-dot ${healthState}`} aria-hidden="true"/>
                        <h2>Backend health</h2>
                    </div>
                    <p className={`status-label ${healthState}`}>{healthState}</p>
                    <dl>
                        {["status", "service", "database"].map((key) => (
                            <div key={key}>
                                <dt>{key}</dt>
                                <dd>{formatValue(health?.[key])}</dd>
                            </div>
                        ))}
                    </dl>
                </article>

                <article className="status-card tutor-card">
                    <div className="card-heading">
                        <span className={`status-dot ${sessionId ? "connected" : "connecting"}`} aria-hidden="true"/>
                        <h2>Tutor Session</h2>
                    </div>
                    <p className="session-status">{activity}</p>
                    <div className="session-form">
                        <label>
                            Learner ID
                            <input value={learnerId} onChange={(event) => setLearnerId(event.target.value)}
                                   inputMode="numeric"/>
                        </label>
                        <label>
                            Journey ID
                            <input value={journeyId} onChange={(event) => setJourneyId(event.target.value)}
                                   inputMode="numeric"/>
                        </label>
                        <button type="button" onClick={() => void createSession()} disabled={loadingSession || sending}>
                            {loadingSession ? "恢复中…" : sessionId ? "重新恢复" : "创建 Session"}
                        </button>
                    </div>
                    {currentLearnUnit && <p className="unit-label">当前 LearnUnit：{currentLearnUnit}</p>}
                    <div className="chat-log" aria-live="polite">
                        {messages.length === 0 && <p className="empty-state">创建 Session 后，在这里开始对话。</p>}
                        {messages.map((message, index) => (
                            <div className={`message ${message.role}`}
                                 key={`${message.timestamp ?? "message"}-${index}`}>
                                <span>{message.role === "user" ? "你" : "TutorAgent"}</span>
                                <p>{message.text || (sending ? "正在组织回答…" : "")}</p>
                            </div>
                        ))}
                    </div>
                    <div className="composer">
                        <textarea
                            value={draft}
                            onChange={(event) => setDraft(event.target.value)}
                            onKeyDown={(event) => {
                                if (event.key === "Enter" && !event.shiftKey) {
                                    event.preventDefault();
                                    void sendMessage();
                                }
                            }}
                            placeholder={sessionId ? "问 TutorAgent 一个问题…" : "请先创建 Tutor Session"}
                            disabled={!sessionId || sending}
                            rows={3}
                        />
                        <div className="composer-actions">
                            <span>Enter 发送 · Shift+Enter 换行</span>
                            {sending ? (
                                <button type="button" className="secondary"
                                        onClick={() => void cancelMessage()}>取消</button>
                            ) : (
                                <button type="button" onClick={() => void sendMessage()}
                                        disabled={!sessionId || !draft.trim()}>发送</button>
                            )}
                        </div>
                    </div>
                    {error && <p className="error-message" role="alert">{error}</p>}
                </article>
            </section>
        </main>
    );
}
