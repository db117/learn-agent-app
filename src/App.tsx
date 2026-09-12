import {type SyntheticEvent, useEffect, useRef, useState} from "react";
import {invoke, isTauri} from "@tauri-apps/api/core";
import {listen} from "@tauri-apps/api/event";
import {type AnswerDraft, AssessmentView, emptyDraft} from "./components/AssessmentView";
import {DashboardView} from "./components/DashboardView";
import {JourneyDraftView} from "./components/JourneyDraftView";
import {ModelSettingsView} from "./components/ModelSettingsView";
import {ResultView} from "./components/ResultView";
import {type JourneyForm, WelcomeView} from "./components/WelcomeView";
import {type BackendStatus, type ImportedState, useDatabaseTransfer} from "./hooks/useDatabaseTransfer";
import {useTutorSession} from "./hooks/useTutorSession";
import {useGenerationRun} from "./hooks/useGenerationRun";
import {
  api,
  type AssessmentResponse,
  type AssessmentResultResponse,
  type BackendHealth,
  type CreateJourneyInput,
  type DiagnosticQuestionPreview,
  type JourneyDetail,
  type JourneyOutlinePreview,
  type LearnUnit,
  type LearnUnitContentPreview,
  type LearnUnitResponse,
  type LearningPhase,
} from "./lib/api";

type View = "welcome" | "journey-draft" | "diagnostic" | "result" | "dashboard" | "assessment" | "settings";
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

function openAttempt(response: LearnUnitResponse) {
  return response.attempts.find((attempt) => attempt.completedAt === null);
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
  const [settingsReturnView, setSettingsReturnView] = useState<View>("welcome");
  const [theme, setTheme] = useState<Theme>(initialTheme);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [draftRunId, setDraftRunId] = useState<string | null>(null);
  const [learnUnitRunId, setLearnUnitRunId] = useState<string | null>(null);
  const [learnUnitRunCode, setLearnUnitRunCode] = useState<string | null>(null);
  const [diagnosticRunId, setDiagnosticRunId] = useState<string | null>(null);
  const [codingRunId, setCodingRunId] = useState<string | null>(null);
  const [form, setForm] = useState<JourneyForm>({
    languageCode: "",
    goal: "Build a practical programming foundation",
    primaryLanguage: "中文",
    experienceYears: "0",
    selfDescription: "",
    learningGoal: "掌握所选语言，并能读写真实项目代码",
  });
  const journeyId = journey?.journey.id;
  const generationRun = useGenerationRun<JourneyOutlinePreview>({
    runId: draftRunId,
    eventsUrl: api.journeyDraftEventsUrl,
    cancel: api.cancelJourneyDraft,
  });
  const draftEvents = generationRun.events;
  const draftOutline = generationRun.preview;
  const draftStatus = generationRun.status === "COMPLETED" ? "CONFIRMED"
    : generationRun.status === "FAILED" ? "FAILED"
      : generationRun.status === "CANCELLED" ? "CANCELLED"
        : generationRun.stage === "WAITING_CONFIRMATION" ? "WAITING_CONFIRMATION"
        : generationRun.stage === "PERSISTING" ? "CONFIRMING" : "GENERATING";
  const learnUnitGeneration = useGenerationRun<LearnUnitContentPreview>({
    runId: learnUnitRunId,
    eventsUrl: api.generationRunEventsUrl,
    cancel: api.cancelGenerationRun,
  });
  const diagnosticGeneration = useGenerationRun<DiagnosticQuestionPreview>({
    runId: diagnosticRunId,
    eventsUrl: api.generationRunEventsUrl,
    cancel: api.cancelGenerationRun,
  });
  const codingGeneration = useGenerationRun<DiagnosticQuestionPreview>({
    runId: codingRunId,
    eventsUrl: api.generationRunEventsUrl,
    cancel: api.cancelGenerationRun,
  });
  const handledDraftCompletion = useRef<string | null>(null);
  const handledLearnUnitCompletion = useRef<string | null>(null);
  const handledDiagnosticCompletion = useRef<string | null>(null);
  const handledCodingCompletion = useRef<string | null>(null);
  const handledCodingFailure = useRef<string | null>(null);
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
    if (learnUnitRunId) void api.cancelGenerationRun(learnUnitRunId).catch(() => undefined);
    if (diagnosticRunId) void api.cancelGenerationRun(diagnosticRunId).catch(() => undefined);
    if (codingRunId) void api.cancelGenerationRun(codingRunId).catch(() => undefined);
    resetTutor();
    setDraftRunId(null);
    setLearnUnitRunId(null);
    setLearnUnitRunCode(null);
    setDiagnosticRunId(null);
    setCodingRunId(null);
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
  const resultLearnUnitCode = assessmentResult?.assessment.learnUnitCode;
  const resultLearnUnitCanRetry = Boolean(resultLearnUnitCode && journey?.path.some((item) =>
    item.learnUnitCode === resultLearnUnitCode &&
    (item.status === "CURRENT" || item.status === "COMPLETED")));
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
            await restoreOpenAssessment(detail);
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
    const next = draftEvents.at(-1);
    if (!draftRunId || !next) return;
    if (next.status === "FAILED" || next.status === "CANCELLED") {
      setBusy(false);
      if (next.status === "FAILED") setError(next.content);
      return;
    }
    if (next.status !== "COMPLETED" || handledDraftCompletion.current === draftRunId) return;
    handledDraftCompletion.current = draftRunId;
    setBusy(false);
    void (async () => {
      try {
        const id = next.resourceId ?? next.runId;
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
  }, [draftEvents, draftRunId]);

  useEffect(() => {
    const next = learnUnitGeneration.events.at(-1);
    if (!learnUnitRunId || !next) return;
    if (next.status === "FAILED" || next.status === "CANCELLED") {
      setBusy(false);
      setError(next.content);
      return;
    }
    if (next.status !== "COMPLETED" || handledLearnUnitCompletion.current === learnUnitRunId) return;
    handledLearnUnitCompletion.current = learnUnitRunId;
    const code = next.resourceId ?? learnUnitRunCode;
    if (!journeyId || !code) return;
    void (async () => {
      try {
        const detail = await api.journey(journeyId);
        setJourney(detail);
        setLearnUnit(await api.learnUnit(journeyId, code));
        setLearnUnitRunId(null);
        setLearnUnitRunCode(null);
        setBusy(false);
        setView("dashboard");
      } catch (cause) {
        setError(errorMessage(cause, "LearnUnit 已生成，但内容加载失败"));
        setBusy(false);
      }
    })();
  }, [journeyId, learnUnitGeneration.events, learnUnitRunCode, learnUnitRunId]);

  useEffect(() => {
    const next = diagnosticGeneration.events.at(-1);
    if (!diagnosticRunId || !next) return;
    if (next.status === "FAILED" || next.status === "CANCELLED") {
      setBusy(false);
      setError(next.content);
      return;
    }
    if (next.status !== "COMPLETED" || handledDiagnosticCompletion.current === diagnosticRunId) return;
    handledDiagnosticCompletion.current = diagnosticRunId;
    const assessmentId = next.resourceId;
    if (!assessmentId) {
      setError("诊断题已生成，但 Assessment 编号缺失");
      setBusy(false);
      return;
    }
    void (async () => {
      try {
        const created = await api.assessment(assessmentId);
        const started = created.openAttempt ? created : await api.startAssessment(assessmentId);
        hydrateAssessment(started);
        setQuestionIndex(firstUnanswered(started));
        setDiagnosticRunId(null);
        setBusy(false);
        setView("diagnostic");
      } catch (cause) {
        setError(errorMessage(cause, "诊断题已保存，但 Assessment 加载失败"));
        setBusy(false);
      }
    })();
  }, [diagnosticGeneration.events, diagnosticRunId]);

  useEffect(() => {
    const next = codingGeneration.events.at(-1);
    if (!codingRunId || !next) return;
    if (next.status === "FAILED" || next.status === "CANCELLED") {
      if (handledCodingFailure.current === codingRunId) return;
      handledCodingFailure.current = codingRunId;
      setBusy(false);
      setError(next.content);
      if (assessment) {
        void api.assessment(assessment.assessment.id).then(hydrateAssessment).catch(() => undefined);
      }
      return;
    }
    if (next.status !== "COMPLETED" || handledCodingCompletion.current === codingRunId) return;
    handledCodingCompletion.current = codingRunId;
    const assessmentId = next.resourceId ?? assessment?.assessment.id;
    if (!assessmentId) {
      setError("Coding 评估已完成，但 Assessment 编号缺失");
      setBusy(false);
      return;
    }
    void (async () => {
      try {
        const result = await api.assessmentResult(assessmentId);
        setAssessmentResult(result);
        if (journeyId) await refreshJourney(journeyId);
        setCodingRunId(null);
        setBusy(false);
        setView("result");
      } catch (cause) {
        setError(errorMessage(cause, "Coding 评估已完成，但结果加载失败"));
        setBusy(false);
      }
    })();
  }, [assessment, codingGeneration.events, codingRunId, journeyId]);
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

  async function restoreOpenAssessment(detail: JourneyDetail) {
    const current = detail.path.find((item) => item.status === "CURRENT");
    const currentResponse = current
      ? await api.learnUnit(detail.journey.id, current.learnUnitCode)
      : null;
    const restore = async (response: LearnUnitResponse | null, assessmentId: string) => {
      const restored = await api.assessment(assessmentId);
      if (!restored.openAttempt) return false;
      setLearnUnit(response);
      hydrateAssessment(restored);
      setQuestionIndex(firstUnanswered(restored));
      setView("assessment");
      return true;
    };
    const currentAttempt = currentResponse && openAttempt(currentResponse);
    if (currentAttempt && await restore(currentResponse, currentAttempt.assessmentId)) return;

    for (const item of detail.path) {
      if (item.status !== "COMPLETED") continue;
      const response = await api.learnUnit(detail.journey.id, item.learnUnitCode);
      const attempt = openAttempt(response);
      if (attempt && await restore(response, attempt.assessmentId)) return;
    }
    setLearnUnit(currentResponse);
    for (const chapter of detail.chapters) {
      if (!chapter.synthesisAssessmentId || chapter.synthesisCompleted) continue;
      if (await restore(null, chapter.synthesisAssessmentId)) return;
    }
    setView("dashboard");
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
      const submitted = await api.submit(assessment.assessment.id);
      if ("runId" in submitted) {
        setCodingRunId(submitted.runId);
        setBusy(false);
        return;
      }
      setAssessmentResult(submitted);
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
      if ("runId" in opened) {
        setLearnUnitRunCode(code);
        setLearnUnitRunId(opened.runId);
        closeTutor();
        setView("dashboard");
        return;
      }
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
      const item = journey?.path.find((pathItem) => pathItem.learnUnitCode === code);
      const opened = await (item?.status === "COMPLETED"
        ? api.reviewLearnUnit(journeyId, code)
        : api.learnUnit(journeyId, code));
      if ("runId" in opened) {
        setLearnUnitRunCode(code);
        setLearnUnitRunId(opened.runId);
        closeTutor();
        setView("dashboard");
        return;
      }
      setLearnUnit(opened);
      closeTutor();
      setView("dashboard");
    } catch (cause) {
      setError(errorMessage(cause, "Unable to open LearnUnit"));
    } finally {
      setBusy(false);
    }
  }

  async function enterAssessment(load: () => Promise<AssessmentResponse>, fallback: string) {
    setBusy(true);
    setError(null);
    try {
      const created = await load();
      const started = created.openAttempt ? created : await api.startAssessment(created.assessment.id);
      hydrateAssessment(started);
      setAssessmentResult(null);
      setQuestionIndex(firstUnanswered(started));
      setView("assessment");
    } catch (cause) {
      setError(errorMessage(cause, fallback));
    } finally {
      setBusy(false);
    }
  }

  async function practiceCompletedLearnUnit(code: string) {
    if (!journeyId) return;
    await enterAssessment(
      () => api.practiceLearnUnit(journeyId, code), "Unable to start LearnUnit practice");
  }

  async function retryCurrentLearnUnit() {
    if (!journeyId || !learnUnit || !currentLearnUnit || !canRetry) return;
    await enterAssessment(
      () => api.retryLearnUnit(journeyId, learnUnit.learnUnit.code), "Unable to retry LearnUnit assessment");
  }

  async function retryAssessmentLearnUnit() {
    const code = assessmentResult?.assessment.learnUnitCode;
    if (!journeyId || !code || !assessmentResult || assessmentResult.passed) return;
    await enterAssessment(
      () => api.retryLearnUnit(journeyId, code), "Unable to retry LearnUnit assessment");
  }

  async function startLearnUnitAssessment() {
    if (!journeyId || !learnUnit || !currentLearnUnit || learnUnit.pathItem?.learningPhase !== "INDEPENDENT_CHECK") return;
    await enterAssessment(
      () => api.learnUnitAssessment(journeyId, learnUnit.learnUnit.code), "Unable to start LearnUnit assessment");
  }

  async function startChapterSynthesis(chapterCode: string) {
    if (!journeyId) return;
    await enterAssessment(
      () => api.chapterSynthesis(journeyId, chapterCode), "Unable to start Chapter synthesis");
  }

  async function retryChapterSynthesis() {
    const chapterCode = assessmentResult?.assessment.chapterCode;
    if (!journeyId || !chapterCode || !assessmentResult || assessmentResult.passed) return;
    await enterAssessment(
      () => api.retryChapterSynthesis(journeyId, chapterCode), "Unable to retry Chapter synthesis");
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

  async function startDiagnostic() {
    if (!journeyId) return;
    setBusy(true);
    setError(null);
    try {
      const started = await api.startDiagnostic(journeyId);
      if (started.runId) {
        setDiagnosticRunId(started.runId);
        setView("diagnostic");
        return;
      }
      if (!started.assessment) throw new Error("诊断入口未返回 Assessment");
      const assessmentToOpen = started.assessment.openAttempt
        ? started.assessment
        : await api.startAssessment(started.assessment.assessment.id);
      hydrateAssessment(assessmentToOpen);
      setQuestionIndex(firstUnanswered(assessmentToOpen));
      setView("diagnostic");
    } catch (cause) {
      setError(errorMessage(cause, "无法开始诊断"));
    } finally {
      setBusy(false);
    }
  }

  function openSettings() {
    if (draftRunId || activeTutorRunId) {
      setError("Agent 正在运行，请等待本次调用结束后再修改模型设置。");
      return;
    }
    setSettingsReturnView(view);
    setView("settings");
  }

  async function applyModelConfiguration() {
    if (!isTauri()) return "配置已保存。开发模式请手动重启 backend 后生效。";
    setBackend({status: "restarting", detail: "127.0.0.1:18080"});
    try {
      const stopped = await invoke<BackendStatus>("stop_backend");
      if (stopped.status === "external") {
        setBackend(stopped);
        return "配置已保存；当前连接的是外部后端，请手动重启它后生效。";
      }
      const started = await invoke<BackendStatus>("start_backend");
      setBackend(started);
      setHealth(await api.health());
      return "配置已保存，后端已重启。";
    } catch (cause) {
      setBackend({status: "error", detail: "127.0.0.1:18080"});
      throw new Error(`配置已保存，但后端重启失败：${errorMessage(cause, "重启失败")}`);
    }
  }

  return (
      <main className={`shell theme-${theme}`}>
      <header className="topbar">
        <div><span className="eyebrow">DESKTOP LEARNING AGENT</span><h1>Learning Journey</h1></div>
        <div className="status-row">
          {journey && <button className="link-button" onClick={() => setView("dashboard")}>我的 Journey</button>}
          {journey && <button className="link-button" onClick={newJourney}>新建</button>}
          <button className="link-button" onClick={openSettings}>模型设置</button>
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
      {view === "settings" && (
          <ModelSettingsView
              onBack={() => setView(settingsReturnView)}
              onSaved={applyModelConfiguration}
          />
      )}
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
              stage={generationRun.stage}
              elapsedMs={generationRun.elapsedMs}
              connection={generationRun.connection}
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
              theme={theme}
              busy={busy}
              generation={view === "diagnostic" ? {
                events: diagnosticGeneration.events,
                status: diagnosticGeneration.status,
                stage: diagnosticGeneration.stage,
                elapsedMs: diagnosticGeneration.elapsedMs,
                connection: diagnosticGeneration.connection,
                preview: diagnosticGeneration.preview,
                onCancel: diagnosticGeneration.cancel,
              } : {
                events: codingGeneration.events,
                status: codingGeneration.status,
                stage: codingGeneration.stage,
                elapsedMs: codingGeneration.elapsedMs,
                connection: codingGeneration.connection,
                preview: null,
                onCancel: codingGeneration.cancel,
              }}
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
                  (assessmentResult.assessment.type === "CHAPTER_SYNTHESIS" || resultLearnUnitCanRetry))}
              onNext={nextLearnUnit}
              onContinue={continueToDashboard}
              onRetry={() => assessmentResult?.assessment.type === "CHAPTER_SYNTHESIS"
                  ? retryChapterSynthesis() : retryAssessmentLearnUnit()}
              onOpenReview={openReviewLearnUnit}
          />
      )}
      {view === "dashboard" && (
          <DashboardView
              journey={journey}
              learnUnits={learnUnits}
              learnUnit={learnUnit}
              busy={busy}
              generationActive={learnUnitRunId !== null && learnUnitGeneration.status === "RUNNING"}
              diagnosticGenerationActive={diagnosticRunId !== null && diagnosticGeneration.status === "RUNNING"}
              generation={{
                events: learnUnitGeneration.events,
                status: learnUnitGeneration.status,
                stage: learnUnitGeneration.stage,
                elapsedMs: learnUnitGeneration.elapsedMs,
                connection: learnUnitGeneration.connection,
                preview: learnUnitGeneration.preview,
                onCancel: async () => {
                  await learnUnitGeneration.cancel();
                },
              }}
              currentLearnUnit={currentLearnUnit}
              hasOpenAttempt={hasOpenAttempt}
              canRetry={Boolean(canRetry)}
              learnUnitLabel={learnUnitLabel}
              onOpenLearnUnit={openLearnUnit}
              onOpenReviewLearnUnit={openReviewLearnUnit}
              onPracticeCompletedLearnUnit={practiceCompletedLearnUnit}
              onStartChapterSynthesis={startChapterSynthesis}
              onStartDiagnostic={startDiagnostic}
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
