import {useEffect, useState} from "react";

const BACKEND_URL = "http://127.0.0.1:18080";
type Health = Record<string, unknown>;
type Connection = "connecting" | "connected" | "error";
type RuntimeEvent = { event: string; data: string };
const RUNTIME_EVENT_TYPES = [
    "message.delta", "skill.loaded", "tool.started", "tool.completed", "tool.failed",
    "plan.created", "plan.updated", "permission.requested", "permission.resolved",
    "subagent.started", "subagent.completed", "workspace.changed", "practice.verified", "error",
];

function formatValue(value: unknown) {
    if (value === undefined || value === null) return "—";
    return typeof value === "string" ? value : JSON.stringify(value);
}

function summarizeEvent(message: MessageEvent<string>): RuntimeEvent {
    let data = message.data;
    try {
        const parsed = JSON.parse(message.data) as Record<string, unknown>;
        data = formatValue(parsed.data ?? parsed.message ?? parsed.type ?? parsed);
    } catch {
        // SSE data is allowed to be plain text.
    }
    return {event: message.type || "message", data};
}

export default function App() {
    const [health, setHealth] = useState<Health | null>(null);
    const [healthState, setHealthState] = useState<"loading" | "online" | "error">("loading");
    const [connection, setConnection] = useState<Connection>("connecting");
    const [runtimeEvent, setRuntimeEvent] = useState<RuntimeEvent | null>(null);

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
            .catch((error: Error) => {
                if (error.name !== "AbortError") setHealthState("error");
            });

        const events = new EventSource(`${BACKEND_URL}/events`);
        events.onopen = () => setConnection("connected");
        const showEvent = (message: Event) => setRuntimeEvent(summarizeEvent(message as MessageEvent<string>));
        events.onmessage = showEvent;
        RUNTIME_EVENT_TYPES.forEach((type) => events.addEventListener(type, showEvent));
        events.onerror = () => setConnection("error");

        return () => {
            controller.abort();
            RUNTIME_EVENT_TYPES.forEach((type) => events.removeEventListener(type, showEvent));
            events.close();
        };
    }, []);

    return (
        <main className="app-shell">
            <section className="hero">
                <p className="eyebrow">LEARN AGENT V2 · RUNTIME SKELETON</p>
                <h1>Runtime is ready to learn.</h1>
                <p className="intro">A small shell for checking the backend and watching runtime activity.</p>
            </section>

            <section className="status-grid" aria-label="Runtime status">
                <article className="status-card">
                    <div className="card-heading">
                        <span className={`status-dot ${healthState}`} aria-hidden="true"/>
                        <h2>Backend health</h2>
                    </div>
                    <p className={`status-label ${healthState}`}>{healthState}</p>
                    <dl>
                        {["status", "service", "database", "config"].map((key) => (
                            <div key={key}>
                                <dt>{key}</dt>
                                <dd>{formatValue(health?.[key])}</dd>
                            </div>
                        ))}
                    </dl>
                </article>

                <article className="status-card">
                    <div className="card-heading">
                        <span className={`status-dot ${connection}`} aria-hidden="true"/>
                        <h2>Event stream</h2>
                    </div>
                    <p className={`status-label ${connection}`}>{connection}</p>
                    <p className="endpoint">GET {BACKEND_URL}/events</p>
                    <div className="event-preview" aria-live="polite">
                        <span>{runtimeEvent?.event ?? "waiting"}</span>
                        <code>{runtimeEvent?.data ?? "No runtime event received yet."}</code>
                    </div>
                </article>
            </section>
        </main>
    );
}
