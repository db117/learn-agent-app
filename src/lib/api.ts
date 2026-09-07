export type BackendHealth = {
  status: string;
  sqlite: string;
  adk: string;
  llm: string;
};

export type SessionSummary = {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
};

export type Message = {
  id: string;
  sessionId: string;
  role: "user" | "assistant";
  content: string;
  createdAt: string;
};

export type AgentEvent = {
  id: string;
  sessionId: string;
  runId: string;
  author: string;
  eventType: "message" | "tool_call" | "tool_result" | "error";
  content: string;
  toolCall?: string;
  toolResult?: string;
  timestamp: string;
};

export type SessionDetail = SessionSummary & {
  messages: Message[];
};

const API_BASE = "http://127.0.0.1:18080/api";

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: {"Content-Type": "application/json"},
    ...init,
  });
  if (!response.ok) {
    throw new Error((await response.text()) || `${response.status} ${response.statusText}`);
  }
  return response.json() as Promise<T>;
}

export const api = {
  health: () => request<BackendHealth>("/health"),
  sessions: () => request<SessionSummary[]>("/sessions"),
  session: (id: string) => request<SessionDetail>(`/sessions/${id}`),
  createSession: () => request<SessionSummary>("/sessions", {method: "POST", body: "{}"}),
  sendMessage: (id: string, content: string) =>
      request<{ runId: string; messageId: string }>(`/sessions/${id}/messages`, {
        method: "POST",
        body: JSON.stringify({content}),
      }),
  eventsUrl: (id: string) => `${API_BASE}/sessions/${id}/events`,
};
