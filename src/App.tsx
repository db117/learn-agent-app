import {useEffect, useRef, useState} from "react";
import {invoke} from "@tauri-apps/api/core";
import {
  api,
  type TutorEvent,
  type AssessmentResponse,
  type AssessmentResultResponse,
  type BackendHealth,
  type CreateJourneyInput,
  type JourneyDetail,
  type LearnUnit,
  type Message,
  type SessionDetail,
  type LearnUnitResponse,
} from "./lib/api";

type BackendStatus = { status: string; detail?: string };
type View = "welcome" | "diagnostic" | "result" | "dashboard" | "assessment";
type AnswerDraft = { selectedOptionIds: string[]; submittedCode: string };
type QuestionOption = { id: string; text: string };
type QuestionConfig = { options: QuestionOption[]; multiple: boolean };

const emptyDraft: AnswerDraft = {selectedOptionIds: [], submittedCode: ""};

function errorMessage(cause: unknown, fallback: string) {
  return cause instanceof Error ? cause.message : fallback;
}

function parseQuestionConfig(raw: string | null): QuestionConfig {
  if (!raw) return {options: [], multiple: false};
  try {
    const parsed = JSON.parse(raw) as { options?: unknown; multiple?: unknown };
    const options = Array.isArray(parsed.options)
      ? parsed.options.filter((option): option is QuestionOption =>
        typeof option === "object" && option !== null &&
        typeof (option as { id?: unknown }).id === "string" &&
        typeof (option as { text?: unknown }).text === "string")
      : [];
    return {options, multiple: parsed.multiple === true};
  } catch {
    return {options: [], multiple: false};
  }
}

function stringArray(raw: string | null) {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw) as unknown;
    return Array.isArray(parsed) && parsed.every((item) => typeof item === "string") ? parsed : [];
  } catch {
    return [];
  }
}

function answerDrafts(response: AssessmentResponse) {
  return response.questionAttempts.reduce<Record<string, AnswerDraft>>((drafts, attempt) => {
    drafts[attempt.questionId] = {
      selectedOptionIds: stringArray(attempt.selectedOptionIdsJson),
      submittedCode: attempt.submittedCode ?? "",
    };
    return drafts;
  }, {});
}

function firstUnanswered(response: AssessmentResponse) {
  const answered = new Set(response.questionAttempts.map((attempt) => attempt.questionId));
  const index = response.questions.findIndex((question) => !answered.has(question.id));
  return index < 0 ? 0 : index;
}

function learnUnitLabel(learnUnits: LearnUnit[], code: string) {
  return learnUnits.find((learnUnit) => learnUnit.code === code)?.name ?? code.split(".").pop() ?? code;
}

function statusLabel(status: string) {
  return status.toLowerCase().replaceAll("_", " ");
}

function eventLabel(event: TutorEvent) {
  switch (event.eventType) {
    case "skill_load_start": return `加载 Skill${event.skillName ? ` · ${event.skillName}` : ""}`;
    case "skill_load_complete": return `Skill 已加载${event.skillName ? ` · ${event.skillName}` : ""}`;
    case "reasoning_summary": return "处理中摘要";
    case "tool_call": return "工具调用";
    case "tool_result": return "工具结果";
    case "text_delta": return "回答";
    case "error": return "错误";
    case "complete": return "完成";
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

export default function App() {
  const [health, setHealth] = useState<BackendHealth | null>(null);
  const [backend, setBackend] = useState<BackendStatus>({status: "checking"});
  const [learnUnits, setLearnUnits] = useState<LearnUnit[]>([]);
  const [journey, setJourney] = useState<JourneyDetail | null>(null);
  const [learnUnit, setLearnUnit] = useState<LearnUnitResponse | null>(null);
  const [assessment, setAssessment] = useState<AssessmentResponse | null>(null);
  const [assessmentResult, setAssessmentResult] = useState<AssessmentResultResponse | null>(null);
  const [answers, setAnswers] = useState<Record<string, AnswerDraft>>({});
  const [questionIndex, setQuestionIndex] = useState(0);
  const [view, setView] = useState<View>("welcome");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [form, setForm] = useState({
    languageCode: "",
    goal: "Build a practical programming foundation",
    primaryLanguage: "中文",
    experienceYears: "0",
    selfDescription: "",
    learningGoal: "掌握所选语言，并能读写真实项目代码",
  });
  const [tutor, setTutor] = useState<SessionDetail | null>(null);
  const [tutorInput, setTutorInput] = useState("");
  const [events, setEvents] = useState<TutorEvent[]>([]);
  const eventSource = useRef<EventSource | null>(null);

  const journeyId = journey?.journey.id;
  const currentQuestion = assessment?.questions[questionIndex] ?? null;
  const currentDraft = currentQuestion ? answers[currentQuestion.id] ?? emptyDraft : emptyDraft;
  const questionConfig = currentQuestion ? parseQuestionConfig(currentQuestion.configJson) : {options: [], multiple: false};

  useEffect(() => {
    let disposed = false;
    let retryTimer: number | undefined;

    async function load() {
      try {
        const [nextHealth, existingJourneys] = await Promise.all([
          api.health(),
          api.journeys(),
        ]);
        if (disposed) return;
        setHealth(nextHealth);
        const existing = existingJourneys[0];
        if (existing) {
          const detail = await api.journey(existing.id);
          if (disposed) return;
          setJourney(detail);
          if (detail.path.length > 0) {
            setView("dashboard");
            const current = detail.path.find((item) => item.status === "CURRENT");
            if (current) {
              setLearnUnit(await api.learnUnit(detail.journey.id, current.learnUnitCode));
            }
          }
        }
        setError(null);
      } catch (cause) {
        if (!disposed) {
          setError(errorMessage(cause, "Backend unavailable"));
          retryTimer = window.setTimeout(() => void load(), 1000);
        }
      }
    }

    void load();
    void invoke<BackendStatus>("backend_status").then(setBackend).catch(() => setBackend({status: "jvm-dev"}));
    return () => {
      disposed = true;
      if (retryTimer !== undefined) window.clearTimeout(retryTimer);
      eventSource.current?.close();
    };
  }, []);

  useEffect(() => {
    if (!journey?.journey.id) return;
    void api.journeyLearnUnits(journey.journey.id).then(setLearnUnits).catch(() => undefined);
  }, [journey?.journey.id]);

  useEffect(() => {
    eventSource.current?.close();
    if (!tutor) {
      setEvents([]);
      return;
    }
    const source = new EventSource(api.eventsUrl(tutor.id));
    eventSource.current = source;
    source.onmessage = (event) => {
      const next = JSON.parse(event.data) as TutorEvent;
      setEvents((current) => current.some((item) => item.id === next.id) ? current : [...current, next]);
      if (next.eventType === "complete") {
        void api.session(tutor.id).then(setTutor).catch(() => undefined);
      }
    };
    source.onerror = () => source.close();
    return () => {
      source.close();
      if (eventSource.current === source) eventSource.current = null;
    };
  }, [tutor?.id]);

  async function refreshJourney(id: string) {
    const previousCode = learnUnit?.learnUnit.code;
    const detail = await api.journey(id);
    setJourney(detail);
    const current = detail.path.find((item) => item.status === "CURRENT");
    if (current) {
      setLearnUnit(await api.learnUnit(id, current.learnUnitCode));
    } else {
      setLearnUnit(null);
    }
    if (previousCode !== current?.learnUnitCode) setTutor(null);
    return detail;
  }

  function hydrateAssessment(next: AssessmentResponse) {
    setAssessment(next);
    setAnswers((current) => ({...current, ...answerDrafts(next)}));
  }

  async function beginDiagnostic(id: string) {
    setBusy(true);
    setError(null);
    try {
      const created = await api.diagnostic(id);
      const started = created.openAttempt ? created : await api.startAssessment(created.assessment.id);
      hydrateAssessment(started);
      setAssessmentResult(null);
      setQuestionIndex(firstUnanswered(started));
      setView("diagnostic");
    } catch (cause) {
      setError(errorMessage(cause, "Unable to start diagnostic"));
    } finally {
      setBusy(false);
    }
  }

  async function createJourney(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    const input: CreateJourneyInput = {
      languageCode: form.languageCode,
      goal: form.goal.trim(),
      primaryLanguage: form.primaryLanguage.trim(),
      experienceYears: form.experienceYears.trim() ? Number(form.experienceYears) : null,
      selfDescription: form.selfDescription.trim(),
      learningGoal: form.learningGoal.trim() || form.goal.trim(),
    };
    try {
      const created = await api.createJourney(input);
      const detail = await api.journey(created.id);
      setJourney(detail);
      await beginDiagnostic(created.id);
    } catch (cause) {
      setError(errorMessage(cause, "Journey 生成失败，请检查输入后重试"));
      setBusy(false);
    }
  }

  async function saveCurrentAnswer() {
    if (!assessment || !currentQuestion) return true;
    const draft = answers[currentQuestion.id] ?? emptyDraft;
    setBusy(true);
    setError(null);
    try {
      const next = await api.answer(assessment.assessment.id, {
        questionId: currentQuestion.id,
        selectedOptionIds: draft.selectedOptionIds,
        submittedCode: draft.submittedCode,
      });
      hydrateAssessment(next);
      return true;
    } catch (cause) {
      setError(errorMessage(cause, "Unable to save answer"));
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function submitAssessment() {
    if (!assessment) return;
    if (!(await saveCurrentAnswer())) return;
    setBusy(true);
    setError(null);
    try {
      const result = await api.submit(assessment.assessment.id);
      setAssessmentResult(result);
      if (journeyId) await refreshJourney(journeyId);
      setView("result");
    } catch (cause) {
      setError(errorMessage(cause, "Unable to submit assessment"));
    } finally {
      setBusy(false);
    }
  }

  async function advanceQuestion() {
    if (!assessment) return;
    if (questionIndex + 1 < assessment.questions.length) {
      if (!(await saveCurrentAnswer())) return;
      setQuestionIndex((current) => current + 1);
    } else {
      await submitAssessment();
    }
  }

  async function goBackQuestion() {
    if (questionIndex === 0) return;
    if (await saveCurrentAnswer()) setQuestionIndex((current) => current - 1);
  }

  async function openLearnUnit(code: string) {
    if (!journeyId) return;
    setBusy(true);
    setError(null);
    try {
      const opened = await api.startLearnUnit(journeyId, code);
      setLearnUnit(opened);
      setTutor(null);
      await refreshJourney(journeyId);
      setView("dashboard");
    } catch (cause) {
      setError(errorMessage(cause, "Unable to open LearnUnit"));
    } finally {
      setBusy(false);
    }
  }

  async function startLearnUnitAssessment() {
    if (!journeyId || !learnUnit) return;
    setBusy(true);
    setError(null);
    try {
      const created = await api.learnUnitAssessment(journeyId, learnUnit.learnUnit.code);
      const started = created.openAttempt ? created : await api.startAssessment(created.assessment.id);
      hydrateAssessment(started);
      setAssessmentResult(null);
      setQuestionIndex(firstUnanswered(started));
      setView("assessment");
    } catch (cause) {
      setError(errorMessage(cause, "Unable to start LearnUnit assessment"));
    } finally {
      setBusy(false);
    }
  }

  async function skipCurrentLearnUnit() {
    if (!journeyId || !learnUnit || !window.confirm("Skip this LearnUnit? It will remain in your history and will not count as mastered.")) return;
    setBusy(true);
    setError(null);
    try {
      setTutor(null);
      await api.skipLearnUnit(journeyId, learnUnit.learnUnit.code);
      await refreshJourney(journeyId);
    } catch (cause) {
      setError(errorMessage(cause, "Unable to skip LearnUnit"));
    } finally {
      setBusy(false);
    }
  }

  async function continueToDashboard() {
    if (!journeyId) return;
    setBusy(true);
    try {
      await refreshJourney(journeyId);
      setView("dashboard");
    } catch (cause) {
      setError(errorMessage(cause, "Unable to load learning path"));
    } finally {
      setBusy(false);
    }
  }

  async function openTutor() {
    if (!journeyId || !learnUnit) return;
    setBusy(true);
    setError(null);
    try {
      const linked = await api.tutor(journeyId, learnUnit.learnUnit.code);
      setTutor(await api.session(linked.session.id));
    } catch (cause) {
      setError(errorMessage(cause, "Unable to open tutor"));
    } finally {
      setBusy(false);
    }
  }

  async function sendTutorMessage(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const content = tutorInput.trim();
    if (!tutor || !content) return;
    setTutorInput("");
    const message: Message = {
      id: `local-${Date.now()}`,
      sessionId: tutor.id,
      role: "user",
      content,
      createdAt: new Date().toISOString(),
    };
    setTutor((current) => current && {...current, messages: [...current.messages, message]});
    try {
      await api.sendMessage(tutor.id, content);
    } catch (cause) {
      setError(errorMessage(cause, "Unable to send tutor message"));
    }
  }

  function newJourney() {
    setJourney(null);
    setLearnUnit(null);
    setAssessment(null);
    setAssessmentResult(null);
    setTutor(null);
    setView("welcome");
  }

  function renderWelcome() {
    if (journey) {
      return (
        <section className="onboarding panel">
          <div className="section-kicker">RESUME JOURNEY</div>
          <h2>{journey.journey.goal}</h2>
          <p className="lead">你的 {journey.journey.languageCode} 学习 Journey 已保存。完成一次诊断后，系统会按 LearnUnit 前置关系生成路径。</p>
          <div className="resume-meta">
            <span className="status-pill">{statusLabel(journey.journey.status)}</span>
            <span>{journey.profile?.learningGoal || "尚未开始诊断"}</span>
          </div>
          <div className="button-row">
            <button className="primary" onClick={() => void beginDiagnostic(journey.journey.id)} disabled={busy}>继续诊断</button>
            <button className="secondary" onClick={newJourney} disabled={busy}>新建 Journey</button>
          </div>
        </section>
      );
    }
    return (
      <section className="onboarding panel">
        <div className="section-kicker">LEARNING JOURNEY · PHASE 2</div>
        <h2>从目标开始，走一条真正属于你的学习路径。</h2>
        <p className="lead">先提出你想学习的语言并填写背景。Agent 会按目标生成 LearnUnit 教学内容和题目；诊断题集会固定保存，评分和路径由后端确定性规则负责。</p>
        <form onSubmit={(event) => void createJourney(event)}>
          <div className="form-grid">
            <label>学习语言
              <input value={form.languageCode} onChange={(event) => setForm((current) => ({...current, languageCode: event.target.value}))} placeholder="例如：Python、Rust、TypeScript" required />
            </label>
            <label>你的主要语言
              <input value={form.primaryLanguage} onChange={(event) => setForm((current) => ({...current, primaryLanguage: event.target.value}))} required />
            </label>
            <label>相关经验（年）
              <input type="number" min="0" max="100" value={form.experienceYears} onChange={(event) => setForm((current) => ({...current, experienceYears: event.target.value}))} />
            </label>
            <label>Journey 名称
              <input value={form.goal} onChange={(event) => setForm((current) => ({...current, goal: event.target.value}))} required />
            </label>
          </div>
          <label>你想达成什么
            <textarea value={form.learningGoal} onChange={(event) => setForm((current) => ({...current, learningGoal: event.target.value}))} rows={3} required />
          </label>
          <label>当前水平补充
                <textarea value={form.selfDescription} onChange={(event) => setForm((current) => ({...current, selfDescription: event.target.value}))} rows={3} placeholder="例如：有后端开发经验，希望系统掌握所选语言。" />
          </label>
          {busy && <p className="generation-status" role="status">正在生成 Journey、LearnUnit 教学内容和适用题目，请稍候…</p>}
          <button className="primary wide" type="submit" disabled={busy || !form.languageCode.trim()}>{busy ? "生成中…" : "创建 Journey 并开始诊断"}</button>
        </form>
      </section>
    );
  }

  function renderAssessment() {
    if (!assessment || !currentQuestion) return <p className="empty">正在准备题目…</p>;
    const diagnostic = assessment.assessment.type === "DIAGNOSTIC";
    return (
      <section className="assessment panel">
        <div className="assessment-header">
          <div>
            <div className="section-kicker">{diagnostic ? "DIAGNOSTIC" : "LEARN UNIT CHECK"}</div>
            <h2>{diagnostic ? "了解你的起点" : learnUnit?.learnUnit.name ?? "LearnUnit 评估"}</h2>
          </div>
          <span className="muted">{questionIndex + 1} / {assessment.questions.length}</span>
        </div>
        <div className="progress-bar"><span style={{width: `${((questionIndex + 1) / assessment.questions.length) * 100}%`}} /></div>
        <div className="question-card">
          <div className="question-meta"><span>{currentQuestion.type === "CODING" ? "CODING" : "MULTIPLE CHOICE"}</span><span>{learnUnitLabel(learnUnits, currentQuestion.learnUnitCode)} · {currentQuestion.points} pts</span></div>
          <h3>{currentQuestion.prompt}</h3>
          {currentQuestion.type === "MULTIPLE_CHOICE" && (
            <div className="options">
              {questionConfig.options.map((option) => {
                const checked = currentDraft.selectedOptionIds.includes(option.id);
                return (
                  <label className={`option ${checked ? "chosen" : ""}`} key={option.id}>
                    <input
                      type={questionConfig.multiple ? "checkbox" : "radio"}
                      name={currentQuestion.id}
                      checked={checked}
                      onChange={() => setAnswers((current) => {
                        const previous = current[currentQuestion.id]?.selectedOptionIds ?? [];
                        const selectedOptionIds = questionConfig.multiple
                          ? previous.includes(option.id) ? previous.filter((id) => id !== option.id) : [...previous, option.id]
                          : [option.id];
                        return {...current, [currentQuestion.id]: {...(current[currentQuestion.id] ?? emptyDraft), selectedOptionIds}};
                      })}
                    />
                    <span><b>{option.id}</b>{option.text}</span>
                  </label>
                );
              })}
            </div>
          )}
          {currentQuestion.type === "CODING" && (
            <div className="coding-answer">
              {currentQuestion.starterCode && <pre>{currentQuestion.starterCode}</pre>}
              <textarea
                value={currentDraft.submittedCode}
                onChange={(event) => setAnswers((current) => ({
                  ...current,
                  [currentQuestion.id]: {...(current[currentQuestion.id] ?? emptyDraft), submittedCode: event.target.value},
                }))}
                rows={12}
                placeholder="在这里写下你的代码…"
                spellCheck={false}
              />
            </div>
          )}
        </div>
        <div className="assessment-actions">
          <button className="secondary" onClick={() => void goBackQuestion()} disabled={busy || questionIndex === 0}>上一题</button>
          <span className="muted">答案会保存到 SQLite</span>
          <button className="primary" onClick={() => void advanceQuestion()} disabled={busy}>{busy ? "保存中…" : questionIndex + 1 === assessment.questions.length ? "提交评估" : "保存并继续"}</button>
        </div>
      </section>
    );
  }

  function renderResult() {
    if (!assessmentResult) return null;
    return (
      <section className="result panel">
        <div className="section-kicker">ASSESSMENT COMPLETE</div>
        <h2>{assessmentResult.assessment.type === "DIAGNOSTIC" ? "你的学习路径已经准备好了" : "LearnUnit 评估完成"}</h2>
        <div className="score-summary">
          <strong>{assessmentResult.score.totalScore}</strong><span>/ 100</span>
          <p className={assessmentResult.passed ? "success" : "warning"}>{assessmentResult.passed ? "通过" : "需要继续练习"}</p>
        </div>
        {assessmentResult.learnUnitResults.length > 0 && (
          <div className="result-list">
            {assessmentResult.learnUnitResults.map((item) => (
              <div className="result-row" key={item.learnUnitCode}>
                <span>{learnUnitLabel(learnUnits, item.learnUnitCode)}</span>
                <span>{item.score.totalScore} · {item.passed ? "已掌握" : "进入路径"}</span>
              </div>
            ))}
          </div>
        )}
        <div className="button-row result-actions">
          <button className="primary" onClick={() => void continueToDashboard()} disabled={busy}>{busy ? "加载路径…" : "进入学习路径"}</button>
          {!assessmentResult.passed && assessmentResult.assessment.type === "LEARN_UNIT" && (
            <button className="secondary" onClick={() => void startLearnUnitAssessment()} disabled={busy}>Retry LearnUnit</button>
          )}
        </div>
      </section>
    );
  }

  function renderTutorPanel() {
    if (!learnUnit) return <aside className="tutor panel"><div className="panel-title">Tutor</div><p className="empty">选择当前 LearnUnit 后，Tutor 会在这里出现。</p></aside>;
    return (
      <aside className="tutor panel">
        <div className="panel-title"><span>Tutor</span><span className="muted">{tutor ? "linked" : "ready"}</span></div>
        {!tutor ? (
          <div className="tutor-empty">
            <p>围绕当前 LearnUnit 提问。Tutor 会读取你的目标、掌握度和最近答题反馈。</p>
            <button className="secondary wide" onClick={() => void openTutor()} disabled={busy}>打开 Tutor</button>
          </div>
        ) : (
          <>
            <div className="messages">
              {tutor.messages.map((message) => <article className={`message ${message.role}`} key={message.id}>
                <span className="message-role">{message.role === "user" ? "你" : "Tutor"}</span>
                <p>{message.content}</p>
              </article>)}
              {!tutor.messages.length && <p className="empty">问一个关于当前 LearnUnit 的问题。</p>}
            </div>
            <form className="composer" onSubmit={(event) => void sendTutorMessage(event)}>
              <textarea value={tutorInput} onChange={(event) => setTutorInput(event.target.value)} placeholder="例如：如何理解这个概念？" rows={3} />
              <button className="primary" type="submit" disabled={!tutorInput.trim()}>发送</button>
            </form>
            <details className="events-details"><summary>Agent events ({events.length})</summary>
              {events.slice(-8).map((event) => isProgressEvent(event) ? (
                <details className={`event event-progress ${event.eventType}`} key={event.id} open={event.eventType === "skill_load_start"}>
                  <summary><span>{eventLabel(event)}</span>{event.status && <small>{event.status}</small>}</summary>
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

  function renderDashboard() {
    const path = journey?.path ?? [];
    const completed = path.filter((item) => item.status === "COMPLETED" || item.status === "SKIPPED").length;
    const current = learnUnit;
    const next = path.find((item) => item.status === "PENDING");
    const nextStep = next
      ? learnUnitLabel(learnUnits, next.learnUnitCode)
      : current?.pathItem?.status === "CURRENT" ? "完成当前 LearnUnit" : "完成 Journey";
    return (
      <section className="journey-grid">
        <aside className="path panel">
          <div className="panel-title"><span>Learning path</span><span className="muted">{completed}/{path.length}</span></div>
          <div className="path-list">
            {path.map((item) => {
              const actionable = item.status === "CURRENT";
              return <button className={`path-item ${item.status.toLowerCase()}`} key={item.learnUnitCode} onClick={() => actionable && void openLearnUnit(item.learnUnitCode)} disabled={!actionable || busy}>
                <span className="path-number">{item.sequence}</span>
                <span><strong>{learnUnitLabel(learnUnits, item.learnUnitCode)}</strong><small>{statusLabel(item.status)}</small></span>
                <span className="path-mark">{item.status === "COMPLETED" ? "✓" : item.status === "SKIPPED" ? "–" : item.status === "CURRENT" ? "→" : "·"}</span>
              </button>;
            })}
            {!path.length && <p className="empty">完成诊断后生成路径。</p>}
          </div>
        </aside>
        <section className="lesson panel">
          {!current ? (
            <div className="empty">{journey?.journey.status === "COMPLETED" ? "恭喜，你已完成这条学习路径。" : "正在加载当前 LearnUnit…"}</div>
          ) : (
            <>
              <div className="lesson-header"><div><div className="section-kicker">CURRENT LEARN UNIT</div><h2>{current.learnUnit.name}</h2></div><span className="status-pill">{statusLabel(current.pathItem?.status ?? "CURRENT")}</span></div>
              <p className="lead">{current.learnUnit.lessonIntro}</p>
              <div className="lesson-stats">
                <span>掌握度 {current.pathItem?.masteryScore ?? 0}</span>
                <span>最佳成绩 {current.pathItem?.bestAssessmentScore ?? 0}</span>
                <span>评估次数 {current.pathItem?.attemptCount ?? 0}</span>
              </div>
              <p className="next-step">下一步：{nextStep}</p>
              <div className="lesson-columns">
                <div><h3>学习目标</h3><ul>{current.learnUnit.learningObjectives.map((item) => <li key={item}>{item}</li>)}</ul></div>
                <div><h3>关键概念</h3><div className="tag-list">{current.learnUnit.keyConcepts.map((item) => <span key={item}>{item}</span>)}</div></div>
              </div>
              <div className="example-block"><h3>Example</h3>{current.learnUnit.examples.map((item) => <p key={item}>{item}</p>)}</div>
              {current.questionAttempts.filter((item) => item.feedback?.trim()).slice(0, 3).length > 0 && (
                <div className="feedback-block"><h3>最近反馈</h3>{current.questionAttempts.filter((item) => item.feedback?.trim()).slice(0, 3).map((item) => <p key={`${item.assessmentAttemptId}-${item.questionId}`}>{item.feedback}</p>)}</div>
              )}
              <div className="button-row">
                <button className="primary" onClick={() => void startLearnUnitAssessment()} disabled={busy || current.pathItem?.status !== "CURRENT"}>开始 LearnUnit 评估</button>
                <button className="secondary" onClick={() => void skipCurrentLearnUnit()} disabled={busy || current.pathItem?.status !== "CURRENT"}>跳过</button>
              </div>
            </>
          )}
        </section>
        {renderTutorPanel()}
      </section>
    );
  }

  return (
    <main className="shell">
      <header className="topbar">
        <div><span className="eyebrow">DESKTOP LEARNING AGENT</span><h1>Learning Journey</h1></div>
        <div className="status-row">
          {journey && <button className="link-button" onClick={() => setView("dashboard")}>我的 Journey</button>}
          {journey && <button className="link-button" onClick={newJourney}>新建</button>}
          <span className={`dot ${health?.status === "UP" ? "ok" : "warn"}`} />
          <span>{health?.status ?? "offline"}</span>
          <span className="muted">{backend.status} · {health?.sqlite ?? "SQLite"}</span>
        </div>
      </header>
      {error && <div className="error-banner" role="alert">{error}</div>}
      {view === "welcome" && renderWelcome()}
      {(view === "diagnostic" || view === "assessment") && renderAssessment()}
      {view === "result" && renderResult()}
      {view === "dashboard" && renderDashboard()}
    </main>
  );
}
