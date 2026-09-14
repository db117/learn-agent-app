import {useState} from "react";
import {invoke, isTauri} from "@tauri-apps/api/core";
import {AssessmentView, emptyDraft} from "./components/AssessmentView";
import {DashboardView} from "./components/DashboardView";
import {JourneyDraftView} from "./components/JourneyDraftView";
import {ModelSettingsView} from "./components/ModelSettingsView";
import {ResultView} from "./components/ResultView";
import {WelcomeView} from "./components/WelcomeView";
import {type BackendStatus, useDatabaseTransfer} from "./hooks/useDatabaseTransfer";
import {errorMessage, learnUnitLabel, useLearningWorkflow} from "./hooks/useLearningWorkflow";
import {api, type BackendHealth,} from "./lib/api";

type Theme = "dark" | "light";

const THEME_STORAGE_KEY = "learning-journey-theme";

function initialTheme(): Theme {
  try {
    const storedTheme = window.localStorage.getItem(THEME_STORAGE_KEY);
    if (storedTheme === "dark" || storedTheme === "light") return storedTheme;
  } catch {
    // 受限 WebView 可能禁止访问 localStorage；读取失败时退回系统主题，不阻断页面启动。
  }
  return window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
}

function saveTheme(theme: Theme) {
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, theme);
  } catch {
    // localStorage 不可用时仍保留本次会话的主题状态，只是不持久化到下次启动。
  }
}

export default function App() {
  const [health, setHealth] = useState<BackendHealth | null>(null);
  const [backend, setBackend] = useState<BackendStatus>({status: "checking"});
  const [theme, setTheme] = useState<Theme>(initialTheme);
  const workflow = useLearningWorkflow({setBackend, setHealth});
  const {
    learnUnits,
    journey,
    learnUnit,
    assessment,
    assessmentResult,
    setAnswers,
    questionIndex,
    view,
    setView,
    settingsReturnView,
    busy,
    error,
    setError,
    form,
    setForm,
    currentLearnUnit,
    hasOpenAttempt,
    canRetry,
    resultLearnUnitCanRetry,
    currentQuestion,
    currentDraft,
    draftEvents,
    draftOutline,
    draftStatus,
    generationRun,
    learnUnitRunId,
    learnUnitGeneration,
    diagnosticRunId,
    diagnosticGeneration,
    codingGeneration,
    tutor,
    tutorInput,
    events,
    activeTutorRunId,
    openTutor,
    sendTutorMessage,
    cancelTutorRun,
    setTutorInput,
  } = workflow;
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
    resetLearningState: workflow.resetLearningState,
    restoreImportedState: workflow.restoreImportedState,
  });

  function toggleTheme() {
    const nextTheme = theme === "dark" ? "light" : "dark";
    setTheme(nextTheme);
    saveTheme(nextTheme);
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
          {journey && <button className="link-button" onClick={workflow.newJourney}>新建</button>}
          <button className="link-button" onClick={workflow.openSettings}>模型设置</button>
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
              onCreateJourney={workflow.createJourney}
              onOpenJourney={workflow.continueToDashboard}
              onNewJourney={workflow.newJourney}
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
              onSendGuidance={workflow.sendDraftGuidance}
              onConfirm={workflow.confirmDraft}
              onCancel={workflow.cancelDraft}
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
              onBack={workflow.goBackQuestion}
              onNext={workflow.advanceQuestion}
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
              onNext={workflow.nextLearnUnit}
              onContinue={workflow.continueToDashboard}
              onRetry={() => assessmentResult?.assessment.type === "CHAPTER_SYNTHESIS"
                  ? workflow.retryChapterSynthesis() : workflow.retryAssessmentLearnUnit()}
              onOpenReview={workflow.openReviewLearnUnit}
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
              onOpenLearnUnit={workflow.openLearnUnit}
              onOpenReviewLearnUnit={workflow.openReviewLearnUnit}
              onPracticeCompletedLearnUnit={workflow.practiceCompletedLearnUnit}
              onStartChapterSynthesis={workflow.startChapterSynthesis}
              onStartDiagnostic={workflow.startDiagnostic}
              onRetryCurrentLearnUnit={workflow.retryCurrentLearnUnit}
              onStartLearnUnitAssessment={workflow.startLearnUnitAssessment}
              onAdvancePhase={workflow.advancePhase}
              onSkipPhase={workflow.skipPhase}
              onGuidedPractice={workflow.recordGuidedPractice}
              onSkipCurrentLearnUnit={workflow.skipCurrentLearnUnit}
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
