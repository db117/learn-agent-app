import {type SyntheticEvent, useEffect, useState} from "react";
import {invoke, isTauri} from "@tauri-apps/api/core";
import {listen} from "@tauri-apps/api/event";
import {type AnswerDraft, AssessmentView, emptyDraft} from "./components/AssessmentView";
import {DashboardView} from "./components/DashboardView";
import {JourneyDraftView} from "./components/JourneyDraftView";
import {ResultView} from "./components/ResultView";
import {type JourneyForm, WelcomeView} from "./components/WelcomeView";
import {type BackendStatus, type ImportedState, useDatabaseTransfer} from "./hooks/useDatabaseTransfer";
import {useTutorSession} from "./hooks/useTutorSession";
import {
  api,
  type AssessmentResponse,
  type AssessmentResultResponse,
  type BackendHealth,
  type CreateJourneyInput,
  type JourneyDetail,
  type JourneyDraftEvent,
  type JourneyDraftOutline,
  type LearnUnit,
  type LearnUnitResponse,
  type LearningPhase,
} from "./lib/api";

type View = "welcome" | "journey-draft" | "diagnostic" | "result" | "dashboard" | "assessment";
type Theme = "dark" | "light";

const THEME_STORAGE_KEY = "learning-journey-theme";

function initialTheme(): Theme {
  try {
    const storedTheme = window.localStorage.getItem(THEME_STORAGE_KEY);
    if (storedTheme === "dark" || storedTheme === "light") return storedTheme;
  } catch {
    // Storage can be unavailable in restricted webviews; use the system preference instead.
  }
  return window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
}

function saveTheme(theme: Theme) {
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, theme);
  } catch {
    // The selected theme still applies for this session when storage is unavailable.
  }
}

function errorMessage(cause: unknown, fallback: string) {
  return cause instanceof Error ? cause.message : typeof cause === "string" ? cause : fallback;
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

function learnUnitLabel(learnUnits: LearnUnit[], code: string | null) {
  if (!code) return "Chapter synthesis";
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
  const [theme, setTheme] = useState<Theme>(initialTheme);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [draftRunId, setDraftRunId] = useState<string | null>(null);
  const [draftEvents, setDraftEvents] = useState<JourneyDraftEvent[]>([]);
  const [draftOutline, setDraftOutline] = useState<JourneyDraftOutline | null>(null);
  const [draftStatus, setDraftStatus] = useState("GENERATING");
  const [form, setForm] = useState<JourneyForm>({
    languageCode: "",
    goal: "Build a practical programming foundation",
    primaryLanguage: "中文",
    experienceYears: "0",
    selfDescription: "",
    learningGoal: "掌握所选语言，并能读写真实项目代码",
  });
  const journeyId = journey?.journey.id;
  const {
    tutor,
    tutorInput,
    events,
    activeTutorRunId,
    openTutor,
    sendTutorMessage,
    cancelTutorRun,
    closeTutor,
    resetTutor,
    setTutorInput,
  } = useTutorSession({journeyId, learnUnit, setBusy, setError, errorMessage});

  function resetLearningState() {
    if (draftRunId) void api.cancelJourneyDraft(draftRunId).catch(() => undefined);
    resetTutor();
    setDraftRunId(null);
    setDraftEvents([]);
    setDraftOutline(null);
    setJourney(null);
    setLearnUnits([]);
    setLearnUnit(null);
    setAssessment(null);
    setAssessmentResult(null);
    setAnswers({});
    setView("welcome");
  }

  function restoreImportedState(state: ImportedState) {
    setHealth(state.health);
    setJourney(state.journey);
    setLearnUnit(state.learnUnit);
    setView(state.view);
  }

  const {
    exportStatus,
    exportMessage,
    importStatus,
    importMessage,
    importInput,
    exportDatabase,
    importDatabase,
  } = useDatabaseTransfer({
    setBackend,
    setError,
    errorMessage,
    resetLearningState,
    restoreImportedState,
  });
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
    };
  }, []);

  useEffect(() => {
    if (!draftRunId) return;
    let terminal = false;
    const source = new EventSource(api.journeyDraftEventsUrl(draftRunId));
    source.onmessage = (message) => {
      try {
        const next = JSON.parse(message.data) as JourneyDraftEvent;
        setDraftEvents((current) => current.some((item) => item.sequence === next.sequence) ? current : [...current, next]);
        setDraftStatus(next.status);
        if (next.outline) setDraftOutline(next.outline);
        if (next.eventType === "error" || next.eventType === "cancelled") {
          terminal = true;
          setBusy(false);
          if (next.eventType === "error") {
            setDraftOutline(null);
            setError(next.content);
          }
        }
        if (next.eventType === "confirmed") {
          terminal = true;
          setBusy(false);
          void (async () => {
            try {
              const id = next.journeyId ?? next.runId;
              const detail = await api.journey(id);
              setJourney(detail);
              const current = detail.path.find((item) => item.status === "CURRENT");
              setLearnUnit(current ? await api.learnUnit(id, current.learnUnitCode) : null);
              setDraftRunId(null);
              setView("dashboard");
            } catch (cause) {
              setError(errorMessage(cause, "Journey 已保存，但学习路径加载失败"));
              setBusy(false);
            }
          })();
        }
      } catch {
        setError("无法读取 Journey 草稿事件");
      }
    };
    source.onerror = () => {
      if (!terminal) setError("Agent 对话连接中断，请重试");
    };
    return () => source.close();
  }, [draftRunId]);

  useEffect(() => {
    if (!journey?.journey.id) return;
    void api.journeyLearnUnits(journey.journey.id).then(setLearnUnits).catch(() => undefined);
  }, [journey?.journey.id]);

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
    if (previousCode !== current?.learnUnitCode) closeTutor();
    return detail;
  }

  function hydrateAssessment(next: AssessmentResponse) {
    setAssessment(next);
    setAnswers((current) => ({...current, ...answerDrafts(next)}));
  }

  async function createJourney(event: SyntheticEvent<HTMLFormElement>) {
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
      const started = await api.startJourneyDraft(input);
      setDraftRunId(started.runId);
      setDraftEvents([]);
      setDraftOutline(null);
      setDraftStatus("GENERATING");
      setView("journey-draft");
    } catch (cause) {
      setError(errorMessage(cause, "Journey 生成失败，请检查输入后重试"));
    } finally {
      setBusy(false);
    }
  }

  async function sendDraftGuidance(content: string) {
    if (!draftRunId) return;
    setBusy(true);
    setError(null);
    try {
      await api.guideJourneyDraft(draftRunId, content);
    } catch (cause) {
      setError(errorMessage(cause, "无法把调整要求发送给 Agent"));
    } finally {
      setBusy(false);
    }
  }

  async function confirmDraft() {
    if (!draftRunId) return;
    setBusy(true);
    setError(null);
    try {
      await api.confirmJourneyDraft(draftRunId);
    } catch (cause) {
      setError(errorMessage(cause, "无法确认 Journey 大纲"));
      setBusy(false);
    }
  }

  async function cancelDraft() {
    if (draftRunId) {
      try {
        await api.cancelJourneyDraft(draftRunId);
      } catch (cause) {
        setError(errorMessage(cause, "无法取消 Journey 草稿"));
        return;
      }
    }
    setDraftRunId(null);
    setDraftEvents([]);
    setDraftOutline(null);
    setView("welcome");
    setBusy(false);
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
      closeTutor();
      await refreshJourney(journeyId);
      setView("dashboard");
    } catch (cause) {
      setError(errorMessage(cause, "Unable to open LearnUnit"));
    } finally {
      setBusy(false);
    }
  }

  async function openReviewLearnUnit(code: string) {
    if (!journeyId) return;
    setBusy(true);
    setError(null);
    try {
      setLearnUnit(await api.learnUnit(journeyId, code));
      closeTutor();
      setView("dashboard");
    } catch (cause) {
      setError(errorMessage(cause, "Unable to open review LearnUnit"));
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
    if (!journeyId || !learnUnit || !currentLearnUnit || learnUnit.pathItem?.learningPhase !== "INDEPENDENT_CHECK") return;
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

  async function startChapterSynthesis(chapterCode: string) {
    if (!journeyId) return;
    setBusy(true);
    setError(null);
    try {
      const created = await api.chapterSynthesis(journeyId, chapterCode);
      const started = created.openAttempt ? created : await api.startAssessment(created.assessment.id);
      hydrateAssessment(started);
      setAssessmentResult(null);
      setQuestionIndex(firstUnanswered(started));
      setView("assessment");
    } catch (cause) {
      setError(errorMessage(cause, "Unable to start Chapter synthesis"));
    } finally {
      setBusy(false);
    }
  }

  async function retryChapterSynthesis() {
    const chapterCode = assessmentResult?.assessment.chapterCode;
    if (!journeyId || !chapterCode || !assessmentResult || assessmentResult.passed) return;
    setBusy(true);
    setError(null);
    try {
      const retried = await api.retryChapterSynthesis(journeyId, chapterCode);
      hydrateAssessment(retried);
      setAssessmentResult(null);
      setQuestionIndex(firstUnanswered(retried));
      setView("assessment");
    } catch (cause) {
      setError(errorMessage(cause, "Unable to retry Chapter synthesis"));
    } finally {
      setBusy(false);
    }
  }

  async function advancePhase(phase: LearningPhase) {
    if (!journeyId || !learnUnit || !currentLearnUnit) return;
    setBusy(true);
    setError(null);
    try {
      const updated = await api.advancePhase(journeyId, learnUnit.learnUnit.code, phase);
      setLearnUnit(updated);
      await refreshJourney(journeyId);
    } catch (cause) {
      setError(errorMessage(cause, "Unable to advance learning phase"));
    } finally {
      setBusy(false);
    }
  }

  async function skipPhase(phase: LearningPhase) {
    if (!journeyId || !learnUnit || !currentLearnUnit) return;
    setBusy(true);
    setError(null);
    try {
      const updated = await api.skipPhase(journeyId, learnUnit.learnUnit.code, phase);
      setLearnUnit(updated);
      await refreshJourney(journeyId);
    } catch (cause) {
      setError(errorMessage(cause, "Unable to skip learning phase"));
    } finally {
      setBusy(false);
    }
  }

  async function recordGuidedPractice(response: string) {
    if (!journeyId || !learnUnit || !currentLearnUnit) return;
    setBusy(true);
    setError(null);
    try {
      const updated = await api.guidedPractice(journeyId, learnUnit.learnUnit.code, response);
      setLearnUnit(updated);
      await refreshJourney(journeyId);
    } catch (cause) {
      setError(errorMessage(cause, "Unable to save guided practice"));
    } finally {
      setBusy(false);
    }
  }

  async function skipCurrentLearnUnit() {
    if (!journeyId || !learnUnit || !currentLearnUnit || !window.confirm("Skip this LearnUnit? It will remain in your history and will not count as mastered.")) return;
    setBusy(true);
    setError(null);
    try {
      closeTutor();
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

  function newJourney() {
    if (draftRunId) void api.cancelJourneyDraft(draftRunId).catch(() => undefined);
    setDraftRunId(null);
    setDraftEvents([]);
    setDraftOutline(null);
    setJourney(null);
    setLearnUnit(null);
    setAssessment(null);
    setAssessmentResult(null);
    closeTutor();
    setView("welcome");
  }

  function toggleTheme() {
    const nextTheme = theme === "dark" ? "light" : "dark";
    setTheme(nextTheme);
    saveTheme(nextTheme);
  }

  return (
      <main className={`shell theme-${theme}`}>
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
          <button className="link-button theme-toggle" type="button" onClick={toggleTheme}
                  aria-label={theme === "dark" ? "切换到浅色模式" : "切换到暗色模式"}>
            {theme === "dark" ? "切换到浅色模式" : "切换到暗色模式"}
          </button>
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
              onOpenJourney={continueToDashboard}
              onNewJourney={newJourney}
          />
      )}
      {view === "journey-draft" && (
          <JourneyDraftView
              events={draftEvents}
              outline={draftOutline}
              status={draftStatus}
              busy={busy}
              onSendGuidance={sendDraftGuidance}
              onConfirm={confirmDraft}
              onCancel={cancelDraft}
          />
      )}
      {(view === "diagnostic" || view === "assessment") && (
          <AssessmentView
              assessment={assessment}
              currentQuestion={currentQuestion}
              questionIndex={questionIndex}
              currentDraft={currentDraft}
              learnUnitName={assessment?.assessment.chapterCode
                  ? journey?.chapters.find((entry) => entry.chapter.code === assessment.assessment.chapterCode)?.chapter.name
                  ?? "Chapter synthesis"
                  : learnUnit?.learnUnit.name ?? "LearnUnit 评估"}
              questionLearnUnitName={currentQuestion
                  ? currentQuestion.chapterCode
                      ? journey?.chapters.find((entry) => entry.chapter.code === currentQuestion.chapterCode)?.chapter.name
                      ?? currentQuestion.chapterCode
                      : learnUnitLabel(learnUnits, currentQuestion.learnUnitCode)
                  : ""}
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
              canRetry={Boolean(assessmentResult && !assessmentResult.passed &&
                  (assessmentResult.assessment.type === "CHAPTER_SYNTHESIS" || canRetry))}
              onNext={nextLearnUnit}
              onContinue={continueToDashboard}
              onRetry={() => assessmentResult?.assessment.type === "CHAPTER_SYNTHESIS"
                  ? retryChapterSynthesis() : retryCurrentLearnUnit()}
              onOpenReview={openReviewLearnUnit}
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
              onOpenReviewLearnUnit={openReviewLearnUnit}
              onStartChapterSynthesis={startChapterSynthesis}
              onRetryCurrentLearnUnit={retryCurrentLearnUnit}
              onStartLearnUnitAssessment={startLearnUnitAssessment}
              onAdvancePhase={advancePhase}
              onSkipPhase={skipPhase}
              onGuidedPractice={recordGuidedPractice}
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
