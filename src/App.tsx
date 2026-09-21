import {useEffect, useRef, useState} from "react";
import {MarkdownMessage} from "./features/agent/MarkdownMessage";
import {PracticeWorkspace} from "./features/practice/PracticeWorkspace";

const BACKEND_URL = "http://127.0.0.1:18080";
const PLANNING_PROMPT = "请开始生成当前 Journey 的学习路径草稿。";
const LEARNING_PROMPT = "请开始当前 LearnUnit。";

type Health = Record<string, unknown>;
type SessionMode = "PLANNING" | "LEARNING";
type TutorMessage = { role: "user" | "assistant"; text: string; timestamp?: string };
type TutorEvent = { type: string; text?: string; errorCode?: string };
type TutorError = { code?: string; message?: string };
type Learner = { id: number; displayName: string; backgroundSummary: string };
type Journey = {
    id: number;
    goalDescription: string;
    status: string;
    current: boolean;
    learningJourneyId: number | null;
    learningJourneyStatus: string | null;
    currentLearnUnitCode: string | null;
};
type Workspace = { kind: string; id: number; reference: string };
type Bootstrap = { learner: Learner | null; journeys: Journey[]; workspace: Workspace | null };

function formatValue(value: unknown) {
    if (value === undefined || value === null) return "—";
    return typeof value === "string" ? value : JSON.stringify(value);
}

function sessionModeFor(journey: Journey): SessionMode {
    return journey.learningJourneyId == null ? "PLANNING" : "LEARNING";
}

async function readError(response: Response) {
    try {
        const error = await response.json() as TutorError;
        return error.message ?? `请求失败（${response.status}）`;
    } catch {
        return `请求失败（${response.status}）`;
    }
}

async function fetchBootstrap(signal?: AbortSignal) {
    const response = await fetch(`${BACKEND_URL}/api/bootstrap`, {signal});
    if (!response.ok) throw new Error(await readError(response));
    return response.json() as Promise<Bootstrap>;
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
    const [bootstrap, setBootstrap] = useState<Bootstrap | null>(null);
    const [bootstrapState, setBootstrapState] = useState<"loading" | "ready" | "error">("loading");
    const [bootstrapError, setBootstrapError] = useState<string | null>(null);
    const [learnerDraft, setLearnerDraft] = useState("");
    const [editingLearner, setEditingLearner] = useState(false);
    const [savingLearner, setSavingLearner] = useState(false);
    const [journeyDraft, setJourneyDraft] = useState("");
    const [journeyAction, setJourneyAction] = useState<"creating" | number | null>(null);
    const [journeyFeedback, setJourneyFeedback] = useState<string | null>(null);
    const [workspaceDirty, setWorkspaceDirty] = useState(false);
    const [workspaceVersion, setWorkspaceVersion] = useState(0);
    const [progressVersion, setProgressVersion] = useState(0);
    const [contentVersion, setContentVersion] = useState(0);
    const [learningCompleted, setLearningCompleted] = useState(false);
    const [planningJourneyId, setPlanningJourneyId] = useState<number | null>(null);
    const [pendingPlanningPrompt, setPendingPlanningPrompt] = useState(false);
    const [pendingLearningPrompt, setPendingLearningPrompt] = useState(false);
    const [confirmingPlan, setConfirmingPlan] = useState(false);
    const [sessionId, setSessionId] = useState<string | null>(null);
    const [sessionMode, setSessionMode] = useState<SessionMode | null>(null);
    const [currentLearnUnit, setCurrentLearnUnit] = useState<string | null>(null);
    const [messages, setMessages] = useState<TutorMessage[]>([]);
    const [draft, setDraft] = useState("");
    const [activity, setActivity] = useState("等待进入 Tutor Session");
    const [error, setError] = useState<string | null>(null);
    const [loadingSession, setLoadingSession] = useState(false);
    const [sending, setSending] = useState(false);
    const streamController = useRef<AbortController | null>(null);
    const sessionTargetRef = useRef<string | null>(null);

    const removePendingAssistant = () => {
        setMessages((current) => current.at(-1)?.role === "assistant" ? current.slice(0, -1) : current);
    };

    const applyBootstrap = (payload: Bootstrap) => {
        setBootstrap(payload);
        setBootstrapState("ready");
        setBootstrapError(null);
        setLearnerDraft(payload.learner?.backgroundSummary ?? "");
        setLearningCompleted(payload.journeys.some(
            (journey) => journey.current && journey.learningJourneyStatus === "COMPLETED",
        ));
    };

    const loadBootstrap = async (signal?: AbortSignal) => {
        setBootstrapState("loading");
        try {
            applyBootstrap(await fetchBootstrap(signal));
        } catch (requestError) {
            if (requestError instanceof Error && requestError.name === "AbortError") return;
            setBootstrapState("error");
            setBootstrapError(requestError instanceof Error ? requestError.message : "无法读取启动数据");
        }
    };

    useEffect(() => {
        const controller = new AbortController();
        void loadBootstrap(controller.signal);
        return () => controller.abort();
    }, []);

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

    const currentJourney = bootstrap?.journeys.find(
        (journey) => journey.current && journey.status === "ACTIVE",
    ) ?? null;
    const activeJourneys = bootstrap?.journeys.filter((journey) => journey.status === "ACTIVE") ?? [];
    const archivedJourneys = bootstrap?.journeys.filter((journey) => journey.status !== "ACTIVE") ?? [];
    const planningOpen = currentJourney !== null
        && (currentJourney.learningJourneyId != null || planningJourneyId === currentJourney.id);
    const sessionTargetKey = bootstrap?.learner && currentJourney && planningOpen && !learningCompleted
    && currentJourney.learningJourneyStatus !== "COMPLETED"
        ? `${bootstrap.learner.id}:${currentJourney.id}:${currentJourney.learningJourneyId ?? "planning"}`
        : null;
    const visibleSessionMode = sessionMode ?? (currentJourney ? sessionModeFor(currentJourney) : null);
    const planningDraftMessage = [...messages].reverse().find(
        (message) => message.role === "assistant" && message.text.trim(),
    );

    const createSession = async (journey: Journey, learnerId: number, targetKey?: string) => {
        setError(null);
        setLoadingSession(true);
        setSessionId(null);
        setSessionMode(null);
        setCurrentLearnUnit(null);
        setMessages([]);
        setDraft("");
        setActivity("正在准备 Tutor Session");
        setLearningCompleted(false);
        try {
            const mode = sessionModeFor(journey);
            const response = await fetch(`${BACKEND_URL}/api/tutor/sessions`, {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({learnerId, journeyId: journey.id, mode}),
            });
            if (!response.ok) throw new Error(await readError(response));
            const session = await response.json() as {
                sessionId: string;
                restored: boolean;
                currentLearnUnitCode: string | null;
                mode: SessionMode;
                messages: TutorMessage[];
            };
            setSessionId(session.sessionId);
            setSessionMode(session.mode);
            setCurrentLearnUnit(session.currentLearnUnitCode);
            setMessages(session.messages);
            setPendingLearningPrompt(session.mode === "LEARNING" && session.messages.length === 0);
            setActivity(session.restored ? "已恢复 Tutor Session" : "Tutor Session 已准备");
        } catch (requestError) {
            if (targetKey && sessionTargetRef.current === targetKey) sessionTargetRef.current = null;
            setError(requestError instanceof Error ? requestError.message : "无法创建 Tutor Session");
        } finally {
            setLoadingSession(false);
        }
    };

    useEffect(() => {
        if (!sessionTargetKey || !bootstrap?.learner || !currentJourney) {
            sessionTargetRef.current = null;
            return;
        }
        if (sessionTargetRef.current === sessionTargetKey) return;
        sessionTargetRef.current = sessionTargetKey;
        void createSession(currentJourney, bootstrap.learner.id, sessionTargetKey);
    }, [sessionTargetKey]);

    const saveLearner = async (event: React.FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        const backgroundSummary = learnerDraft.trim();
        if (!backgroundSummary) {
            setError("请填写背景与能力描述");
            return;
        }
        setError(null);
        setSavingLearner(true);
        try {
            const response = await fetch(`${BACKEND_URL}/api/learner`, {
                method: "PUT",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({backgroundSummary}),
            });
            if (!response.ok) throw new Error(await readError(response));
            setEditingLearner(false);
            await loadBootstrap();
        } catch (requestError) {
            setError(requestError instanceof Error ? requestError.message : "无法保存 Learner 设置");
        } finally {
            setSavingLearner(false);
        }
    };

    const createJourney = async (event: React.FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        const goalDescription = journeyDraft.trim();
        if (!goalDescription) {
            setError("请填写 Journey 目标");
            return;
        }
        setError(null);
        setJourneyFeedback(null);
        setJourneyAction("creating");
        try {
            const response = await fetch(`${BACKEND_URL}/api/journeys`, {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({goalDescription}),
            });
            if (!response.ok) throw new Error(await readError(response));
            const journey = await response.json() as Journey;
            setJourneyDraft("");
            await loadBootstrap();
            setJourneyFeedback(journey.current
                ? "Journey 已创建；点击“生成学习路径”进入规划草稿。"
                : "Journey 已创建；选择它后即可进入规划草稿或学习。");
        } catch (requestError) {
            setError(requestError instanceof Error ? requestError.message : "无法创建 Journey");
        } finally {
            setJourneyAction(null);
        }
    };

    const selectJourney = async (journey: Journey) => {
        if (journey.status !== "ACTIVE" || journey.current || journeyAction !== null) return;
        if (workspaceDirty) {
            setError("当前 Workspace 文件有未保存修改，请先保存。");
            return;
        }
        setError(null);
        setJourneyFeedback(null);
        setJourneyAction(journey.id);
        try {
            const response = await fetch(`${BACKEND_URL}/api/journeys/${journey.id}/select`, {method: "POST"});
            if (!response.ok) throw new Error(await readError(response));
            await loadBootstrap();
            setJourneyFeedback("当前 Journey 已切换。");
        } catch (requestError) {
            setError(requestError instanceof Error ? requestError.message : "无法选择 Journey");
        } finally {
            setJourneyAction(null);
        }
    };

    const confirmPlanning = async () => {
        const journey = currentJourney;
        const plan = planningDraftMessage?.text.trim();
        if (!journey || visibleSessionMode !== "PLANNING" || !plan || confirmingPlan) return;
        setError(null);
        setConfirmingPlan(true);
        try {
            const response = await fetch(`${BACKEND_URL}/api/journeys/${journey.id}/confirm-plan`, {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({plan}),
            });
            if (!response.ok) throw new Error(await readError(response));
            await response.json() as Journey;
            setSessionId(null);
            setSessionMode(null);
            setCurrentLearnUnit(null);
            setMessages([]);
            setDraft("");
            setPlanningJourneyId(null);
            setPendingLearningPrompt(false);
            setLearningCompleted(false);
            setJourneyFeedback("学习路径已保存，正在进入第一课。");
            setActivity("学习路径已确认，正在进入第一课");
            await loadBootstrap();
        } catch (requestError) {
            setError(requestError instanceof Error ? requestError.message : "无法保存学习路径");
        } finally {
            setConfirmingPlan(false);
        }
    };

    const sendMessage = async (messageOverride?: string) => {
        const text = (messageOverride ?? draft).trim();
        if (!sessionId || !text || sending || confirmingPlan) return;
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
                if (event.type === "turn.started") setActivity("TutorAgent 已开始处理");
                if (event.type === "activity") setActivity(event.text ?? "TutorAgent 正在工作");
                if (event.type === "workspace.changed") {
                    setWorkspaceVersion((version) => version + 1);
                }
                if (event.type.startsWith("tool.") || event.type === "workspace.changed") {
                    setActivity(event.text ?? "TutorAgent 正在操作 Workspace");
                }
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
                if (event.type === "turn.completed") {
                    setActivity("TutorAgent 已完成回答");
                    if (visibleSessionMode === "LEARNING") {
                        setContentVersion((version) => version + 1);
                    }
                }
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

    useEffect(() => {
        if (!pendingPlanningPrompt || !sessionId || sessionMode !== "PLANNING" || loadingSession || sending) return;
        if (messages.length > 0) {
            setPendingPlanningPrompt(false);
            return;
        }
        setPendingPlanningPrompt(false);
        void sendMessage(PLANNING_PROMPT);
    }, [pendingPlanningPrompt, sessionId, sessionMode, loadingSession, sending, messages.length]);

    useEffect(() => {
        if (!pendingLearningPrompt || !sessionId || sessionMode !== "LEARNING" || loadingSession || sending) return;
        if (messages.length > 0) {
            setPendingLearningPrompt(false);
            return;
        }
        setPendingLearningPrompt(false);
        void sendMessage(LEARNING_PROMPT);
    }, [pendingLearningPrompt, sessionId, sessionMode, loadingSession, sending, messages.length]);

    const cancelMessage = async () => {
        if (!sessionId || !sending) return;
        try {
            const response = await fetch(`${BACKEND_URL}/api/tutor/sessions/${sessionId}/cancel`, {method: "POST"});
            if (!response.ok) throw new Error(await readError(response));
        } catch (requestError) {
            setError(requestError instanceof Error ? requestError.message : "无法取消 Tutor Turn");
        } finally {
            streamController.current?.abort();
            removePendingAssistant();
            setActivity("Tutor Turn 已取消");
        }
    };

    const handleProgressChanged = (status: string, nextLearnUnitCode: string | null) => {
        setProgressVersion((version) => version + 1);
        if (status === "COMPLETED") {
            setLearningCompleted(true);
            sessionTargetRef.current = null;
            setSessionId(null);
            setSessionMode(null);
            setCurrentLearnUnit(null);
            setMessages([]);
            setActivity("学习路径已完成");
            void loadBootstrap();
            return;
        }

        setLearningCompleted(false);
        if (currentJourney && bootstrap?.learner
            && nextLearnUnitCode !== null && nextLearnUnitCode !== currentLearnUnit) {
            sessionTargetRef.current = null;
            void createSession(currentJourney, bootstrap.learner.id);
        }
        void loadBootstrap();
    };

    const showLearningCard = sessionTargetKey !== null || learningCompleted;

    return (
        <main className="app-shell">
            <section className="hero">
                <p className="eyebrow">LEARN AGENT V2 · TUTOR RUNTIME</p>
                <h1>和 TutorAgent 一起学习。</h1>
                <p className="intro">从你的背景和目标开始，逐步进入适合自己的学习 Journey。</p>
            </section>

            {(bootstrapError || error) && (
                <p className="error-message global-error" role="alert">{bootstrapError ?? error}</p>
            )}

            <section className="status-grid" aria-label="学习环境">
                <article className="status-card compact-card" aria-labelledby="health-title">
                    <div className="card-heading">
                        <span className={`status-dot ${healthState}`} aria-hidden="true"/>
                        <h2 id="health-title">Backend health</h2>
                    </div>
                    <p className={`status-label ${healthState}`} role="status">{healthState}</p>
                    <dl>
                        {["status", "service", "database"].map((key) => (
                            <div key={key}>
                                <dt>{key}</dt>
                                <dd>{formatValue(health?.[key])}</dd>
                            </div>
                        ))}
                    </dl>
                </article>

                {!bootstrap && bootstrapState === "loading" && (
                    <article className="status-card onboarding-card" aria-busy="true">
                        <div className="card-heading">
                            <span className="status-dot loading" aria-hidden="true"/>
                            <h2>正在准备学习环境</h2>
                        </div>
                        <p className="session-status" role="status">正在读取 Learner 与 Journey…</p>
                    </article>
                )}

                {!bootstrap && bootstrapState === "error" && (
                    <article className="status-card onboarding-card">
                        <div className="card-heading">
                            <span className="status-dot error" aria-hidden="true"/>
                            <h2>无法读取学习环境</h2>
                        </div>
                        <p className="session-status">请确认后端已启动，然后重试。</p>
                        <button type="button" onClick={() => void loadBootstrap()}>重试</button>
                    </article>
                )}

                {bootstrap?.learner === null && (
                    <article className="status-card onboarding-card" aria-labelledby="learner-title">
                        <div className="card-heading">
                            <span className="status-dot loading" aria-hidden="true"/>
                            <h2 id="learner-title">先设置你的 Learner</h2>
                        </div>
                        <p className="session-status">告诉 TutorAgent 你的背景、能力和正在学习的内容。</p>
                        <form className="setup-form" onSubmit={(event) => void saveLearner(event)}>
                            <label htmlFor="learner-background">
                                背景与能力描述 <span aria-hidden="true">（必填）</span>
                                <textarea
                                    id="learner-background"
                                    value={learnerDraft}
                                    onChange={(event) => setLearnerDraft(event.target.value)}
                                    placeholder="例如：我会一点 TypeScript，想系统学习 React 和前端工程。"
                                    required
                                    rows={6}
                                />
                            </label>
                            <button type="submit" disabled={savingLearner}>
                                {savingLearner ? "保存中…" : "保存并继续"}
                            </button>
                        </form>
                    </article>
                )}

                {bootstrap?.learner && (
                    <article className="status-card journey-card" aria-labelledby="journey-title">
                        <div className="card-heading">
                            <span className="status-dot connected" aria-hidden="true"/>
                            <h2 id="journey-title">你的 Journey</h2>
                        </div>
                        <p className="profile-summary">
                            <strong>{bootstrap.learner.displayName}</strong> · {bootstrap.learner.backgroundSummary}
                        </p>
                        {!editingLearner && (
                            <div className="profile-actions">
                                <button type="button" className="secondary" onClick={() => setEditingLearner(true)}>
                                    编辑 Learner 背景
                                </button>
                            </div>
                        )}
                        {editingLearner && (
                            <form className="setup-form" onSubmit={(event) => void saveLearner(event)}>
                                <label htmlFor="learner-background-edit">
                                    更新背景与能力描述
                                    <textarea
                                        id="learner-background-edit"
                                        value={learnerDraft}
                                        onChange={(event) => setLearnerDraft(event.target.value)}
                                        required
                                        rows={5}
                                    />
                                </label>
                                <div className="form-actions">
                                    <button type="submit" disabled={savingLearner}>
                                        {savingLearner ? "保存中…" : "保存 Learner"}
                                    </button>
                                    <button type="button" className="secondary" onClick={() => setEditingLearner(false)}
                                            disabled={savingLearner}>
                                        取消
                                    </button>
                                </div>
                            </form>
                        )}
                        <p className="session-status">
                            {activeJourneys.length === 0 ? "先创建一个目标，开始规划你的学习路径。" : "选择一个 active Journey 继续。"}
                        </p>

                        <div className="journey-list" aria-label="Active Journeys">
                            {activeJourneys.map((journey) => (
                                <article
                                    className={`journey-item ${journey.current ? "current" : ""}`}
                                    key={journey.id}
                                    aria-current={journey.current ? "true" : undefined}
                                >
                                    <div className="journey-item-header">
                                        <h3>{journey.goalDescription}</h3>
                                        <span className={`journey-status ${journey.current ? "current" : ""}`}>
                                            {journey.current ? "当前 Journey" : "ACTIVE"}
                                        </span>
                                    </div>
                                    <p className="journey-meta">
                                        {journey.learningJourneyId == null
                                            ? "尚未生成学习路径 · 进入后讨论规划草稿"
                                            : "已有学习路径 · 进入 LEARNING 模式"}
                                    </p>
                                    <div className="journey-actions">
                                        {journey.current && journey.learningJourneyId == null && (
                                            <button
                                                type="button"
                                                onClick={() => {
                                                    setPendingPlanningPrompt(true);
                                                    setPlanningJourneyId(journey.id);
                                                }}
                                                disabled={planningJourneyId === journey.id || journeyAction !== null || loadingSession}
                                                aria-pressed={planningJourneyId === journey.id}
                                            >
                                                {planningJourneyId === journey.id ? "规划草稿已打开" : "生成学习路径"}
                                            </button>
                                        )}
                                        {journey.current && journey.learningJourneyId != null && (
                                            <span className="journey-ready">已进入学习</span>
                                        )}
                                        {!journey.current && (
                                            <button
                                                type="button"
                                                className="secondary"
                                                onClick={() => void selectJourney(journey)}
                                                disabled={journeyAction !== null || loadingSession || sending}
                                            >
                                                {journeyAction === journey.id ? "切换中…" : "选择此 Journey"}
                                            </button>
                                        )}
                                    </div>
                                </article>
                            ))}
                            {activeJourneys.length === 0 && (
                                <p className="empty-state">还没有 active Journey。</p>
                            )}
                        </div>

                        {archivedJourneys.length > 0 && (
                            <details className="archived-section">
                                <summary>已归档 Journey（{archivedJourneys.length}）</summary>
                                <ul className="archived-list">
                                    {archivedJourneys.map((journey) => (
                                        <li key={journey.id}>
                                            <span>{journey.goalDescription}</span>
                                            <span className="journey-status">已归档</span>
                                        </li>
                                    ))}
                                </ul>
                            </details>
                        )}

                        <form className="journey-form" onSubmit={(event) => void createJourney(event)}>
                            <label htmlFor="journey-goal">
                                新建 Journey
                                <textarea
                                    id="journey-goal"
                                    value={journeyDraft}
                                    onChange={(event) => setJourneyDraft(event.target.value)}
                                    placeholder="例如：在 8 周内能独立完成一个 React 项目。"
                                    required
                                    rows={3}
                                />
                            </label>
                            <button type="submit" disabled={journeyAction !== null || loadingSession || sending}>
                                {journeyAction === "creating" ? "创建中…" : "创建 Journey"}
                            </button>
                        </form>
                        {journeyFeedback && <p className="form-feedback" role="status">{journeyFeedback}</p>}
                    </article>
                )}

                {bootstrap?.learner && currentJourney && showLearningCard && (
                    <article className="status-card tutor-card" aria-labelledby="tutor-title">
                        <div className="card-heading card-heading-actions">
                            <div className="card-heading">
                                <span className={`status-dot ${sessionId ? "connected" : "connecting"}`}
                                      aria-hidden="true"/>
                                <h2 id="tutor-title">{visibleSessionMode === "PLANNING" ? "规划草稿" : "Tutor Session"}</h2>
                            </div>
                            <button
                                type="button"
                                className="secondary"
                                onClick={() => void createSession(currentJourney, bootstrap.learner!.id)}
                                disabled={loadingSession || sending || confirmingPlan}
                            >
                                {loadingSession ? "恢复中…" : "重新恢复"}
                            </button>
                        </div>
                        <p className="mode-label">
                            {visibleSessionMode === "PLANNING" ? "PLANNING · 学习路径规划" : "LEARNING · 当前学习路径"}
                        </p>
                        {visibleSessionMode === "PLANNING" ? (
                            <p className="planning-note" role="note">
                                点击生成会先请 TutorAgent 提出一版草案；你可以继续对话调整，内容尚未保存为正式学习路径。
                            </p>
                        ) : (
                            <p className="planning-note" role="note">当前 Journey 已有 LearningJourney；进入当前
                                LearnUnit 后，TutorAgent
                                会生成本单元的讲解和 Practice。</p>
                        )}
                        {visibleSessionMode === "PLANNING" && planningDraftMessage && (
                            <div className="journey-actions">
                                <button
                                    type="button"
                                    onClick={() => void confirmPlanning()}
                                    disabled={confirmingPlan || loadingSession || sending}
                                >
                                    {confirmingPlan ? "保存中…" : "完成设计"}
                                </button>
                            </div>
                        )}
                        {(visibleSessionMode === "LEARNING" || learningCompleted)
                            && currentJourney.learningJourneyId != null && (
                                <PracticeWorkspace
                                    journeyId={currentJourney.id}
                                    onDirtyChange={setWorkspaceDirty}
                                    onProgressChanged={handleProgressChanged}
                                    workspaceVersion={workspaceVersion}
                                    progressVersion={progressVersion}
                                    contentVersion={contentVersion}
                                />
                        )}
                        <p className="session-status" role="status" aria-live="polite">
                            {loadingSession ? "正在恢复消息…" : activity}
                        </p>
                        {currentLearnUnit && <p className="unit-label">当前 LearnUnit：{currentLearnUnit}</p>}
                        <div className="chat-log" aria-live="polite" aria-label="Tutor 对话记录">
                            {messages.length === 0 && (
                                <p className="empty-state">
                                    {learningCompleted
                                        ? "学习路径已完成。"
                                        : loadingSession
                                            ? "正在加载 Session…"
                                            : "发送第一条消息，开始与 TutorAgent 对话。"}
                                </p>
                            )}
                            {messages.map((message, index) => (
                                <div className={`message ${message.role}`}
                                     key={`${message.timestamp ?? "message"}-${index}`}>
                                    <span>{message.role === "user" ? "你" : "TutorAgent"}</span>
                                    {message.role === "assistant" ? (
                                        <MarkdownMessage text={message.text || (sending ? "正在组织回答…" : "")}/>
                                    ) : (
                                        <p>{message.text}</p>
                                    )}
                                </div>
                            ))}
                        </div>
                        <div className="composer">
                            <label className="sr-only" htmlFor="tutor-message">发送给 TutorAgent</label>
                            <textarea
                                id="tutor-message"
                                value={draft}
                                onChange={(event) => setDraft(event.target.value)}
                                onKeyDown={(event) => {
                                    if (event.key === "Enter" && !event.shiftKey) {
                                        event.preventDefault();
                                        void sendMessage();
                                    }
                                }}
                                placeholder={sessionId ? "问 TutorAgent 一个问题…" : "正在准备 Tutor Session…"}
                                disabled={!sessionId || sending || confirmingPlan}
                                rows={3}
                            />
                            <div className="composer-actions">
                                <span>Enter 发送 · Shift+Enter 换行</span>
                                {sending ? (
                                    <button type="button" className="secondary"
                                            onClick={() => void cancelMessage()}>取消</button>
                                ) : (
                                    <button type="button" onClick={() => void sendMessage()}
                                            disabled={!sessionId || !draft.trim() || confirmingPlan}>
                                        发送
                                    </button>
                                )}
                            </div>
                        </div>
                    </article>
                )}
            </section>
        </main>
    );
}
