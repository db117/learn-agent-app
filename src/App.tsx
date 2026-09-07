import {useCallback, useEffect, useRef, useState} from "react";
import {invoke} from "@tauri-apps/api/core";
import {
  type AgentEvent,
  api,
  type BackendHealth,
  type Message,
  type SessionDetail,
  type SessionSummary
} from "./lib/api";

type BackendStatus = { status: string; detail?: string };

export default function App() {
  const [health, setHealth] = useState<BackendHealth | null>(null);
  const [backend, setBackend] = useState<BackendStatus>({status: "checking"});
  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  const [selected, setSelected] = useState<SessionDetail | null>(null);
  const [events, setEvents] = useState<AgentEvent[]>([]);
  const [input, setInput] = useState("");
  const [error, setError] = useState<string | null>(null);
  const source = useRef<EventSource | null>(null);
  const selectedId = selected?.id;

  const refresh = useCallback(async (): Promise<boolean> => {
    try {
      const [nextHealth, nextSessions] = await Promise.all([api.health(), api.sessions()]);
      setHealth(nextHealth);
      setSessions(nextSessions);
      if (!selectedId && nextSessions[0]) {
        setSelected(await api.session(nextSessions[0].id));
      }
      setError(null);
      return true;
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Backend unavailable");
      return false;
    }
  }, [selectedId]);

  useEffect(() => {
    let disposed = false;
    let retryTimer: number | undefined;
    const load = async () => {
      if (await refresh() || disposed) return;
      retryTimer = window.setTimeout(() => void load(), 1000);
    };
    void load();
    void invoke<BackendStatus>("backend_status").then(setBackend).catch(() => setBackend({status: "jvm-dev"}));
    return () => {
      disposed = true;
      if (retryTimer !== undefined) window.clearTimeout(retryTimer);
      source.current?.close();
    };
  }, [refresh]);

  useEffect(() => {
    source.current?.close();
    if (!selected) {
      setEvents([]);
      return;
    }
    const eventSource = new EventSource(api.eventsUrl(selected.id));
    source.current = eventSource;
    eventSource.onmessage = (event) => {
      const next = JSON.parse(event.data) as AgentEvent;
      setEvents((current) => (current.some((item) => item.id === next.id) ? current : [...current, next]));
      if (next.eventType === "message" && next.author !== "user") {
        void api.session(selected.id).then(setSelected).catch(() => undefined);
      }
    };
    return () => {
      eventSource.close();
      if (source.current === eventSource) source.current = null;
    };
  }, [selected?.id]);

  async function selectSession(id: string) {
    try {
      setSelected(await api.session(id));
      setEvents([]);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Unable to open session");
    }
  }

  async function newSession() {
    try {
      const created = await api.createSession();
      setSessions((current) => [created, ...current]);
      setSelected({...created, messages: []});
      setEvents([]);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Unable to create session");
    }
  }

  async function sendMessage() {
    const content = input.trim();
    if (!selected || !content) return;
    setInput("");
    setSelected((current) => current && {
      ...current,
      messages: [...current.messages, {
        id: `local-${Date.now()}`,
        sessionId: current.id,
        role: "user",
        content,
        createdAt: new Date().toISOString()
      }],
    });
    try {
      await api.sendMessage(selected.id, content);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Unable to send message");
    }
  }

  return (
      <main className="shell">
        <header className="topbar">
          <div><span className="eyebrow">PHASE 1</span><h1>Desktop Learning Agent</h1></div>
          <div className="status-row">
            <span className={`dot ${health?.status === "UP" ? "ok" : "warn"}`}/>
            <span>Backend {health?.status ?? "offline"}</span>
            <span className="muted">{backend.status}</span>
          </div>
        </header>
        {error && <div className="error-banner">{error}</div>}
        <section className="workspace">
          <aside className="sessions panel">
            <div className="panel-title"><span>Sessions</span>
              <button onClick={() => void newSession()} aria-label="New session">＋</button>
            </div>
            <div className="session-list">
              {sessions.map((session) => <button
                  className={`session-item ${selected?.id === session.id ? "selected" : ""}`} key={session.id}
                  onClick={() => void selectSession(session.id)}>
                <strong>{session.title}</strong><small>{new Date(session.updatedAt).toLocaleString()}</small>
              </button>)}
              {!sessions.length && <p className="empty">Create a session to begin.</p>}
            </div>
          </aside>
          <section className="chat panel">
            <div className="panel-title"><span>{selected?.title ?? "Tutor chat"}</span><span className="muted">ADK / TutorAgent</span>
            </div>
            <div className="messages">
              {selected?.messages.map((message: Message) => <article className={`message ${message.role}`}
                                                                     key={message.id}><span
                  className="message-role">{message.role === "user" ? "You" : "TutorAgent"}</span>
                <p>{message.content}</p></article>)}
              {!selected && <p className="empty">Select or create a session.</p>}
            </div>
            <form className="composer" onSubmit={(event) => {
              event.preventDefault();
              void sendMessage();
            }}>
                        <textarea value={input} onChange={(event) => setInput(event.target.value)}
                                  placeholder="Ask about a programming language…" disabled={!selected}
                                  onKeyDown={(event) => {
                                    if (event.key === "Enter" && !event.shiftKey) {
                                      event.preventDefault();
                                      void sendMessage();
                                    }
                                  }}/>
              <button type="submit" disabled={!selected || !input.trim()}>Send</button>
            </form>
          </section>
          <aside className="events panel">
            <div className="panel-title"><span>Agent / Tool Events</span><span
                className="muted">{health ? `${health.sqlite} SQLite` : "—"}</span></div>
            <div className="event-list">
              {events.map((event) => <article className={`event ${event.eventType}`} key={event.id}>
                <div><span className="event-type">{event.eventType}</span>
                  <time>{new Date(event.timestamp).toLocaleTimeString()}</time>
                </div>
                <p>{event.content || event.toolCall || event.toolResult || "(empty event)"}</p></article>)}
              {!events.length && <p className="empty">Events will appear here while the agent runs.</p>}
            </div>
          </aside>
        </section>
      </main>
  );
}
