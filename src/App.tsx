import {useEffect, useRef, useState} from "react";
import {invoke, isTauri} from "@tauri-apps/api/core";
import {listen} from "@tauri-apps/api/event";
import {type AnswerDraft, AssessmentView, emptyDraft} from "./components/AssessmentView";
import {DashboardView} from "./components/DashboardView";
import {ResultView} from "./components/ResultView";
import {type JourneyForm, WelcomeView} from "./components/WelcomeView";
import {
  api,
  ApiError,
  type AssessmentResponse,
  type AssessmentResultResponse,
  type BackendHealth,
  type CreateJourneyInput,
  type JourneyDetail,
  type LearnUnit,
  type LearnUnitResponse,
  type Message,
  type SessionDetail,
  type TutorEvent,
} from "./lib/api";

type BackendStatus = { status: string; detail?: string };
type View = "welcome" | "diagnostic" | "result" | "dashboard" | "assessment";

function errorMessage(cause: unknown, fallback: string) {
  return cause instanceof Error ? cause.message : typeof cause === "string" ? cause : fallback;
}

function databaseErrorMessage(cause: unknown, fallback: string) {
    if (!(cause instanceof ApiError)) return errorMessage(cause, fallback);
    switch (cause.payload.error) {
        case "database_agent_busy":
            return "TutorAgent 正在运行，请等待本次调用结束后重试导入。";
        case "database_transfer_busy":
            return "已有数据库导入或导出正在进行，请稍后重试。";
        case "database_import_schema_unknown":
            return "数据库 schema 未知或不兼容，当前数据库未改变。";
        case "database_import_schema_version":
            return "数据库 schema 版本不受支持，当前数据库未改变。";
        case "database_import_invalid_file":
            return "数据库文件损坏或不是有效快照，当前数据库未改变。";
        case "database_import_backup_failed":
            return "无法创建导入前备份，当前数据库未改变。";
        case "database_import_replace_failed":
            return "数据库替换失败，原数据库已恢复。";
        default:
            return cause.message || fallback;
    }
}

function backendConnectionError(cause: unknown) {
  const detail = errorMessage(cause, "connection failed");
  return `无法启动或连接本地 JVM 后端（127.0.0.1:18080）。请确认 Java 21 已安装且可在 PATH 中找到。${detail ? ` ${detail}` : ""}`;
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
  const [form, setForm] = useState<JourneyForm>({
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
  const [activeTutorRunId, setActiveTutorRunId] = useState<string | null>(null);
    const [exportStatus, setExportStatus] = useState<"idle" | "exporting" | "success" | "error">("idle");
    const [exportMessage, setExportMessage] = useState("");
    const [importStatus, setImportStatus] = useState<"idle" | "importing" | "success" | "warning" | "error">("idle");
    const [importMessage, setImportMessage] = useState("");
    const importInput = useRef<HTMLInputElement | null>(null);
  const terminalRuns = useRef(new Set<string>());
    // 使刚被替换数据库产生的延迟 SSE/会话回调失效。
    const runtimeEpoch = useRef(0);

  const journeyId = journey?.journey.id;
  const currentPathItem = journey?.path.find((item) => item.status === "CURRENT") ?? null;
  const currentLearnUnit = Boolean(
    journey?.journey.status === "ACTIVE" &&
    currentPathItem &&
    learnUnit?.learnUnit.code === currentPathItem.learnUnitCode &&
    learnUnit.pathItem?.status === "CURRENT",
  );
  const hasOpenAttempt = currentLearnUnit &&
    (learnUnit?.attempts.some((attempt) => attempt.completedAt === null) ?? false);
  const canRetry = currentLearnUnit && !hasOpenAttempt &&
    (learnUnit?.attempts.some((attempt) => attempt.completedAt !== null && attempt.passed === false) ?? false);
  const currentQuestion = assessment?.questions[questionIndex] ?? null;
  const currentDraft = currentQuestion ? answers[currentQuestion.id] ?? emptyDraft : emptyDraft;

  useEffect(() => {
    let disposed = false;
    let retryTimer: number | undefined;
    let unlistenBackendRequired: (() => void) | undefined;
    const desktop = isTauri();

    async function load() {
      try {
        if (desktop) {
          const nextBackend = await invoke<BackendStatus>("start_backend");
          if (disposed) return;
          setBackend(nextBackend);
        } else {
          setBackend({status: "jvm-dev", detail: "127.0.0.1:18080"});
        }
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
          setError(desktop ? backendConnectionError(cause) : errorMessage(cause, "Backend unavailable"));
          retryTimer = window.setTimeout(() => void load(), 1000);
        }
      }
    }

    if (desktop) {
      void listen<string>("backend-required", (event) => {
        if (disposed) return;
        setBackend({status: "error", detail: "127.0.0.1:18080"});
        setError(`本地 JVM 后端启动失败（127.0.0.1:18080）：${event.payload}`);
      }).then((stop) => {
        if (disposed) stop();
        else unlistenBackendRequired = stop;
      }).catch(() => undefined);
    }
    void load();
    return () => {
      disposed = true;
      unlistenBackendRequired?.();
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
      setActiveTutorRunId(null);
      terminalRuns.current.clear();
      return;
    }
      const epoch = runtimeEpoch.current;
    const source = new EventSource(api.eventsUrl(tutor.id));
    eventSource.current = source;
    source.onmessage = (event) => {
        if (epoch !== runtimeEpoch.current) return;
      const next = JSON.parse(event.data) as TutorEvent;
      setEvents((current) => current.some((item) => item.id === next.id) ? current : [...current, next]);
      if (next.eventType === "complete" || next.eventType === "error" || next.eventType === "cancelled") {
        terminalRuns.current.add(next.runId);
        setActiveTutorRunId((current) => current === next.runId ? null : current);
      }
      if (next.eventType === "complete") {
          void api.session(tutor.id).then((session) => {
              if (epoch === runtimeEpoch.current) setTutor(session);
          }).catch(() => undefined);
      }
    };
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
      const opened = await api.continueLearnUnit(journeyId, code);
      setLearnUnit(opened);
      setTutor(null);
      setActiveTutorRunId(null);
      await refreshJourney(journeyId);
      setView("dashboard");
    } catch (cause) {
      setError(errorMessage(cause, "Unable to open LearnUnit"));
    } finally {
      setBusy(false);
    }
  }

  async function retryCurrentLearnUnit() {
    if (!journeyId || !learnUnit || !currentLearnUnit || !canRetry) return;
    setBusy(true);
    setError(null);
    try {
      const retried = await api.retryLearnUnit(journeyId, learnUnit.learnUnit.code);
      hydrateAssessment(retried);
      setAssessmentResult(null);
      setQuestionIndex(firstUnanswered(retried));
      setView("assessment");
    } catch (cause) {
      setError(errorMessage(cause, "Unable to retry LearnUnit assessment"));
    } finally {
      setBusy(false);
    }
  }

  async function startLearnUnitAssessment() {
    if (!journeyId || !learnUnit || !currentLearnUnit) return;
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
    if (!journeyId || !learnUnit || !currentLearnUnit || !window.confirm("Skip this LearnUnit? It will remain in your history and will not count as mastered.")) return;
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

  async function nextLearnUnit() {
    const closedCode = assessmentResult?.assessment.learnUnitCode;
    if (!journeyId || !closedCode || !assessmentResult?.passed || assessmentResult.assessment.type !== "LEARN_UNIT") return;
    setBusy(true);
    setError(null);
    try {
      await api.nextLearnUnit(journeyId, closedCode);
      await refreshJourney(journeyId);
      setAssessment(null);
      setAssessmentResult(null);
      setView("dashboard");
    } catch (cause) {
      setError(errorMessage(cause, "Unable to continue to the next LearnUnit"));
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
      const sent = await api.sendMessage(tutor.id, content);
      if (!terminalRuns.current.has(sent.runId)) setActiveTutorRunId(sent.runId);
    } catch (cause) {
      setError(errorMessage(cause, "Unable to send tutor message"));
    }
  }

  async function cancelTutorRun() {
    if (!tutor || !activeTutorRunId) return;
    try {
      await api.cancelRun(tutor.id, activeTutorRunId);
      setActiveTutorRunId(null);
    } catch (cause) {
      setError(errorMessage(cause, "Unable to cancel tutor message"));
    }
  }

    async function exportDatabase() {
        setExportStatus("exporting");
        setExportMessage("正在导出…");
        try {
            await api.exportDatabase();
            setExportStatus("success");
            setExportMessage("数据库已导出");
        } catch (cause) {
            setExportStatus("error");
            setExportMessage(databaseErrorMessage(cause, "数据库导出失败"));
        }
    }

    /** 先关闭旧 SSE，再清理所有绑定到已替换数据库的状态对象。 */
    function clearImportedState() {
        runtimeEpoch.current += 1;
        eventSource.current?.close();
        eventSource.current = null;
        terminalRuns.current.clear();
        setJourney(null);
        setLearnUnits([]);
        setLearnUnit(null);
        setAssessment(null);
        setAssessmentResult(null);
        setAnswers({});
        setTutor(null);
        setTutorInput("");
        setEvents([]);
        setActiveTutorRunId(null);
        setView("welcome");
    }

    /** Tauri 重启是异步的；只有固定回环后端健康后才重新加载页面。 */
    async function waitForBackendHealth() {
        let lastError: unknown;
        for (let attempt = 0; attempt < 30; attempt += 1) {
            try {
                return await api.health();
            } catch (cause) {
                lastError = cause;
                await new Promise((resolve) => window.setTimeout(resolve, 200));
            }
        }
        throw lastError ?? new Error("后端健康检查超时");
    }

    /** 导入后通过 HTTP 重建页面，不复用导入前的 React 对象。 */
    async function reloadAfterImport() {
        clearImportedState();
        const nextHealth = await waitForBackendHealth();
        const journeys = await api.journeys();
        setHealth(nextHealth);
        const existing = journeys[0];
        if (!existing) {
            setView("welcome");
            return;
        }
        const detail = await api.journey(existing.id);
        setJourney(detail);
        const current = detail.path.find((item) => item.status === "CURRENT");
        if (current) setLearnUnit(await api.learnUnit(existing.id, current.learnUnitCode));
        setView(detail.path.length ? "dashboard" : "welcome");
    }

    async function importDatabase(event: React.ChangeEvent<HTMLInputElement>) {
        const file = event.target.files?.[0];
        event.target.value = "";
        if (!file) return;
        setImportStatus("importing");
        setImportMessage("正在验证并导入…");
        setError(null);
        try {
            let result;
            try {
                // 第一次请求有意保持非破坏性，用于识别过期快照。
                result = await api.importDatabase(file);
            } catch (cause) {
                if (!(cause instanceof ApiError) || cause.payload.error !== "database_import_stale") throw cause;
                setImportStatus("warning");
                setImportMessage(`快照时间 ${cause.payload.snapshotCreatedAt ?? "未知"}，当前数据库时间 ${cause.payload.currentDatabaseAt ?? "未知"}。请确认是否覆盖当前进度。`);
                if (!window.confirm("这是较旧的数据库快照。确认后将覆盖当前数据库，是否继续？")) return;
                setImportStatus("importing");
                setImportMessage("正在确认并导入…");
                // 只有学习者显式确认后，才允许执行破坏性的数据库替换。
                result = await api.importDatabase(file, true);
            }
            if (result.restartRequired && !isTauri()) {
                clearImportedState();
                setImportStatus("warning");
                setImportMessage("数据库已导入。开发模式需要手动重启本地后端后，页面才会重新加载。");
                return;
            }
            setImportStatus("success");
            setImportMessage(result.restartRequired ? "导入成功，正在重新加载…" : "数据库已导入");
            if (result.restartRequired) {
                try {
                    // SQLite 由 Java 边界负责；Tauri 只重启受管的 JVM 进程。
                    await invoke("stop_backend");
                    const nextBackend = await invoke<BackendStatus>("start_backend");
                    setBackend(nextBackend);
                } catch (cause) {
                    clearImportedState();
                    setImportStatus("error");
                    setImportMessage(`数据库已导入，但后端重启失败，请手动重启后继续：${errorMessage(cause, "重启失败")}`);
                    return;
                }
            }
            await reloadAfterImport();
        } catch (cause) {
            setImportStatus("error");
            setImportMessage(databaseErrorMessage(cause, "数据库导入失败，原数据库未改变"));
        }
    }

  function newJourney() {
    setJourney(null);
    setLearnUnit(null);
    setAssessment(null);
    setAssessmentResult(null);
    setTutor(null);
    setActiveTutorRunId(null);
    setView("welcome");
  }

  return (
    <main className="shell">
      <header className="topbar">
        <div><span className="eyebrow">DESKTOP LEARNING AGENT</span><h1>Learning Journey</h1></div>
        <div className="status-row">
          {journey && <button className="link-button" onClick={() => setView("dashboard")}>我的 Journey</button>}
          {journey && <button className="link-button" onClick={newJourney}>新建</button>}
            <button className="link-button" onClick={() => void exportDatabase()}
                    disabled={exportStatus === "exporting"}>
                {exportStatus === "exporting" ? "导出中…" : "导出数据库"}
            </button>
            <label className="link-button">
                {importStatus === "importing" ? "导入中…" : "导入数据库"}
                <input ref={importInput} type="file" accept=".db" onChange={(event) => void importDatabase(event)}
                       disabled={importStatus === "importing"} hidden/>
            </label>
            {exportStatus !== "idle" &&
                <span className={exportStatus === "error" ? "warning" : "success"} role="status">{exportMessage}</span>}
            {importStatus !== "idle" &&
                <span className={importStatus === "error" || importStatus === "warning" ? "warning" : "success"}
                      role="status">{importMessage}</span>}
          <span className={`dot ${health?.status === "UP" ? "ok" : "warn"}`} />
          <span>{health?.status ?? "offline"}</span>
          <span className="muted">{backend.status} · {backend.detail ?? "127.0.0.1:18080"}</span>
        </div>
      </header>
      {error && <div className="error-banner" role="alert">{error}</div>}
      {view === "welcome" && (
          <WelcomeView
              journey={journey}
              form={form}
              busy={busy}
              onFormChange={(field, value) => setForm((current) => ({...current, [field]: value}))}
              onCreateJourney={createJourney}
              onBeginDiagnostic={beginDiagnostic}
              onNewJourney={newJourney}
          />
      )}
      {(view === "diagnostic" || view === "assessment") && (
          <AssessmentView
              assessment={assessment}
              currentQuestion={currentQuestion}
              questionIndex={questionIndex}
              currentDraft={currentDraft}
              learnUnitName={learnUnit?.learnUnit.name ?? "LearnUnit 评估"}
              questionLearnUnitName={currentQuestion ? learnUnitLabel(learnUnits, currentQuestion.learnUnitCode) : ""}
              busy={busy}
              onSelectedOptionIds={(selectedOptionIds) => {
                if (!currentQuestion) return;
                setAnswers((current) => ({
                  ...current,
                  [currentQuestion.id]: {...(current[currentQuestion.id] ?? emptyDraft), selectedOptionIds},
                }));
              }}
              onCodeChange={(submittedCode) => {
                if (!currentQuestion) return;
                setAnswers((current) => ({
                  ...current,
                  [currentQuestion.id]: {...(current[currentQuestion.id] ?? emptyDraft), submittedCode},
                }));
              }}
              onBack={goBackQuestion}
              onNext={advanceQuestion}
          />
      )}
      {view === "result" && (
          <ResultView
              assessmentResult={assessmentResult}
              learnUnitLabel={(code) => learnUnitLabel(learnUnits, code)}
              busy={busy}
              canNext={Boolean(
                  assessmentResult &&
                  assessmentResult.assessment.type !== "DIAGNOSTIC" &&
                  assessmentResult.passed &&
                  journey?.journey.status === "ACTIVE" &&
                  journey.path.some((item) => item.status === "CURRENT"),
              )}
              canRetry={Boolean(canRetry)}
              onNext={nextLearnUnit}
              onContinue={continueToDashboard}
              onRetry={retryCurrentLearnUnit}
          />
      )}
      {view === "dashboard" && (
          <DashboardView
              journey={journey}
              learnUnits={learnUnits}
              learnUnit={learnUnit}
              busy={busy}
              currentLearnUnit={currentLearnUnit}
              hasOpenAttempt={hasOpenAttempt}
              canRetry={Boolean(canRetry)}
              learnUnitLabel={learnUnitLabel}
              onOpenLearnUnit={openLearnUnit}
              onRetryCurrentLearnUnit={retryCurrentLearnUnit}
              onStartLearnUnitAssessment={startLearnUnitAssessment}
              onSkipCurrentLearnUnit={skipCurrentLearnUnit}
              tutor={{
                learnUnit,
                tutor,
                tutorInput,
                events,
                activeTutorRunId,
                busy,
                onOpenTutor: openTutor,
                onTutorInputChange: setTutorInput,
                onSendTutorMessage: sendTutorMessage,
                onCancelTutorRun: cancelTutorRun,
              }}
          />
      )}
    </main>
  );
}
