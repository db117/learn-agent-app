import {type SyntheticEvent, useEffect, useState} from "react";
import {invoke, isTauri} from "@tauri-apps/api/core";
import {listen} from "@tauri-apps/api/event";
import {type AnswerDraft, AssessmentView, emptyDraft} from "./components/AssessmentView";
import {DashboardView} from "./components/DashboardView";
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
  type LearnUnit,
  type LearnUnitResponse,
} from "./lib/api";

type View = "welcome" | "diagnostic" | "result" | "dashboard" | "assessment";

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
    resetTutor();
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
      closeTutor();
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
    setJourney(null);
    setLearnUnit(null);
    setAssessment(null);
    setAssessmentResult(null);
    closeTutor();
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
