export type BackendHealth = {
  status: string;
  sqlite: string;
  agent: string;
  llm: string;
};

export type ModelConfiguration = {
  provider: string;
  baseUrl: string;
  model: string;
  apiKeyConfigured: boolean;
  apiKeyMasked: string;
  source: "app" | "environment" | "default";
  restartRequired: boolean;
};

export type ModelConfigurationInput = {
  provider: string;
  baseUrl: string;
  model: string;
  apiKey: string | null;
};

export type ModelTestResponse = {
  status: string;
  message: string;
  model: string;
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

export type TutorEvent = {
  sequence: number;
  id: string;
  sessionId: string;
  runId: string;
  author: string;
  eventType:
    | "text_delta"
    | "tool_call"
    | "tool_result"
    | "skill_load_start"
    | "skill_load_complete"
    | "reasoning_summary"
    | "error"
    | "complete"
    | "cancelled";
  content: string;
  toolCall?: string;
  toolResult?: string;
  skillName?: string;
  summary?: string;
  status?: "started" | "completed" | "failed" | "cancelled";
  timestamp: string;
};

export type SessionDetail = SessionSummary & {
  messages: Message[];
};

export type TutorQuestionContext = {
    phase: LearningPhase;
    prompt: string;
    options: Array<{ id: string; text: string }>;
    starterCode: string | null;
    answerDraft: string;
};

export type JourneyStatus = "ACTIVE" | "COMPLETED" | "ARCHIVED";
export type LearningPathItemStatus = "PENDING" | "CURRENT" | "COMPLETED" | "SKIPPED";
export type LearningPhase = "EXPLANATION" | "EXAMPLE" | "GUIDED_PRACTICE" | "INDEPENDENT_CHECK";
export type QuestionType = "MULTIPLE_CHOICE" | "CODING";
export type AssessmentType = "DIAGNOSTIC" | "LEARN_UNIT" | "CHAPTER_SYNTHESIS";
export type AssessmentStatus = "CREATED" | "IN_PROGRESS" | "COMPLETED";

export type LearningLanguage = {
  id: string;
  code: string;
  name: string;
  description: string;
  enabled: boolean;
};

export type Chapter = {
  id: string;
  code: string;
  name: string;
  goal: string;
  sequence: number;
  prerequisiteChapterCodes: string[];
};

export type LearnUnit = {
  id: string;
  languageCode: string;
  code: string;
  chapterCode: string;
  name: string;
  description: string;
  sequence: number;
  prerequisiteLearnUnitCodes: string[];
  passScore: number;
  minCodingScore: number | null;
  enabled: boolean;
  learningObjectives: string[];
  lessonIntro: string;
  keyConcepts: string[];
  examples: string[];
  diagnosticEligible: boolean;
  ability: string;
  estimatedMinutes: number;
  guidedPracticePrompt: string;
  guidedPracticeHints: string[];
  independentCheckPrompt: string;
};

export type LearningJourney = {
  id: string;
  userId: string;
  languageCode: string;
  goal: string;
  status: JourneyStatus;
  createdAt: string;
  updatedAt: string;
};

export type LearnerProfile = {
  journeyId: string;
  primaryLanguage: string;
  experienceYears: number | null;
  selfDescription: string;
  learningGoal: string;
};

export type LearningPathItem = {
  id: string;
  journeyId: string;
  learnUnitCode: string;
  sequence: number;
  status: LearningPathItemStatus;
  masteryScore: number;
  bestAssessmentScore: number;
  attemptCount: number;
  passReason: "DIAGNOSTIC" | "LEARNING" | null;
  startedAt: string | null;
  passedAt: string | null;
  skippedAt: string | null;
  learningPhase: LearningPhase;
  skippedPhases: LearningPhase[];
  guidedPracticeEntries: GuidedPracticeEntry[];
  needsReview: boolean;
};

export type GuidedPracticeEntry = {
  response: string;
  feedback: string;
  createdAt: string;
};

export type Question = {
  id: string;
  learnUnitCode: string | null;
  chapterCode: string | null;
  type: QuestionType;
  difficulty: number;
  prompt: string;
  points: number;
  configJson: string | null;
  rubricJson: string | null;
  language: string | null;
  starterCode: string | null;
  referenceConceptsJson: string | null;
};

export type Assessment = {
  id: string;
  journeyId: string;
  learnUnitCode: string | null;
  chapterCode: string | null;
  type: AssessmentType;
  status: AssessmentStatus;
  createdAt: string;
  completedAt: string | null;
};

export type AssessmentAttempt = {
  id: string;
  assessmentId: string;
  journeyId: string;
  learnUnitCode: string | null;
  attemptNumber: number;
  choiceScore: number | null;
  codingScore: number | null;
  totalScore: number | null;
  passed: boolean | null;
  startedAt: string;
  completedAt: string | null;
};

export type QuestionAttempt = {
  questionId: string;
  assessmentAttemptId: string;
  answerJson: string | null;
  score: number | null;
  maxScore: number;
  feedback: string | null;
  correct: boolean | null;
  submittedCode: string | null;
  evaluationJson: string | null;
  selectedOptionIdsJson: string | null;
};

export type AssessmentScore = {
  choiceScore: number;
  codingScore: number;
  totalScore: number;
  hasChoiceQuestions: boolean;
  hasCodingQuestions: boolean;
};

export type DiagnosticLearnUnitResult = {
  learnUnitCode: string;
  score: AssessmentScore;
  passed: boolean;
  evidenceCount: number;
};

export type AssessmentResponse = {
  assessment: Assessment;
  questions: Question[];
  openAttempt: AssessmentAttempt | null;
  attempts: AssessmentAttempt[];
  questionAttempts: QuestionAttempt[];
};

export type AssessmentResultResponse = {
  assessment: Assessment;
  attempt: AssessmentAttempt;
  score: AssessmentScore;
  passed: boolean;
  learnUnitResults: DiagnosticLearnUnitResult[];
  questionAttempts: QuestionAttempt[];
  passScore: number;
  codingPassScore: number | null;
  reviewLearnUnitCode: string | null;
  chapterCompleted: boolean;
};

export type LearnUnitResponse = {
  journeyId: string;
  learnUnit: LearnUnit;
  pathItem: LearningPathItem | null;
  attempts: AssessmentAttempt[];
  questionAttempts: QuestionAttempt[];
};

export type JourneyDetail = {
  journey: LearningJourney;
  profile: LearnerProfile | null;
  chapters: JourneyChapter[];
  path: LearningPathItem[];
  diagnosticAssessmentId: string | null;
};

export type JourneyChapter = {
  chapter: Chapter;
  learnUnits: LearnUnit[];
  path: LearningPathItem[];
  completedCount: number;
  skippedCount: number;
  unresolvedCount: number;
  synthesisAvailable: boolean;
  synthesisCompleted: boolean;
  synthesisAssessmentId: string | null;
};

export type TutorSessionResponse = {
  session: SessionSummary;
  journeyId: string;
  learnUnitCode: string;
};

export type CreateJourneyInput = {
  languageCode: string;
  goal: string;
  primaryLanguage: string;
  experienceYears: number | null;
  selfDescription: string;
  learningGoal: string;
};

export type JourneyDraftOutline = {
  languages: LearningLanguage[];
  chapters: Chapter[];
  learnUnits: LearnUnit[];
};

export type JourneyOutlinePreview = {
  chapterCount: number;
  learnUnitCount: number;
  chapters: Array<{
    sequence: number;
    name: string;
    goal: string;
    learnUnitCount: number;
    learnUnits: Array<{sequence: number; name: string; description: string}>;
  }>;
};

export type LearnUnitContentPreview = {
  ability: string;
  estimatedMinutes: number;
  lessonIntro: string;
  examples: string[];
  guidedPracticePrompt: string;
  guidedPracticeHints: string[];
  independentCheckPrompt: string;
  independentQuestionCount: number;
};

export type DiagnosticQuestionPreview = {
  questionCount: number;
  questions: Array<{learnUnitCode: string; type: string; stem: string}>;
};

export type GenerationPreview = JourneyOutlinePreview | LearnUnitContentPreview | DiagnosticQuestionPreview;

export type GenerationEvent<TPreview = GenerationPreview> = {
  sequence: number;
  runId: string;
  operation: string;
  stage: string;
  author: string;
  eventType: "run_started" | "user_message" | "agent_message" | "model_preview" | "stage_changed"
    | "validation" | "persistence" | "draft_ready" | "heartbeat" | "completed" | "failed" | "cancelled";
  content: string;
  status: "RUNNING" | "COMPLETED" | "FAILED" | "CANCELLED";
  elapsedMs: number;
  preview: TPreview | null;
  resourceType: string | null;
  resourceId: string | null;
  timestamp: string;
};

export type JourneyDraftEvent = GenerationEvent<JourneyOutlinePreview>;

export type JourneyDraftStartResponse = {runId: string};
export type AssessmentSubmitResponse = AssessmentResultResponse | JourneyDraftStartResponse;
export type LearnUnitEntryResponse = LearnUnitResponse | JourneyDraftStartResponse;
export type JourneyDraftAck = {status: string};
export type DiagnosticStartResponse = {runId: string | null; assessment: AssessmentResponse | null};

/** 完整数据库替换成功后的结果；是否重启 Tauri 由界面决定。 */
export type DatabaseImportResponse = {
  schemaVersion: string;
  importedAt: string;
  restartRequired: boolean;
};

/** 项目错误信封；过期快照确认需要的两个时间戳也包含在其中。 */
export type ApiErrorPayload = {
  error?: string;
  detail?: string;
  snapshotCreatedAt?: string;
  currentDatabaseAt?: string;
  confirmationRequired?: boolean;
};

export class ApiError extends Error {
  constructor(public readonly status: number, public readonly payload: ApiErrorPayload, fallback: string) {
    super(payload.detail || payload.error || fallback);
  }
}

const API_BASE = "http://127.0.0.1:18080/api";

/** 将响应下载为浏览器文件；后端仍然是唯一读取 SQLite 的组件。 */
async function exportDatabase() {
  const response = await fetch(`${API_BASE}/database/export`);
  if (!response.ok) throw await apiError(response, `数据库导出失败（${response.status}）`);
  const blob = await response.blob();
  const filename = response.headers.get("Content-Disposition")?.match(/filename="?([^";]+)"?/)?.[1] ?? "learning-agent-java.db";
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(link.href), 0);
}

/** 将选中文件作为适合流式处理的请求体发送；过期数据必须显式确认。 */
async function importDatabase(file: File, confirm = false) {
  return request<DatabaseImportResponse>(`/database/import${confirm ? "?confirm=true" : ""}`, {
    method: "POST",
    headers: {"Content-Type": "application/octet-stream"},
    body: file,
  });
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: {"Content-Type": "application/json"},
    ...init,
  });
  if (!response.ok) throw await apiError(response, `${response.status} ${response.statusText}`);
  return response.json() as Promise<T>;
}

/** 保留后端错误码，使界面能够展示稳定且可操作的提示。 */
async function apiError(response: Response, fallback: string): Promise<never> {
  const raw = await response.text();
  let payload: ApiErrorPayload = {};
  try {
    payload = JSON.parse(raw) as ApiErrorPayload;
  } catch {
    payload = {detail: raw};
  }
  throw new ApiError(response.status, payload, fallback);
}

export const api = {
  exportDatabase,
  importDatabase,
  health: () => request<BackendHealth>("/health"),
  modelConfiguration: () => request<ModelConfiguration>("/settings/model"),
  saveModelConfiguration: (input: ModelConfigurationInput) => request<ModelConfiguration>("/settings/model", {
    method: "PUT",
    body: JSON.stringify(input),
  }),
  testModelConfiguration: (input: ModelConfigurationInput) => request<ModelTestResponse>("/settings/model/test", {
    method: "POST",
    body: JSON.stringify(input),
  }),
  languages: () => request<LearningLanguage[]>("/learning/languages"),
  journeyLearnUnits: (journeyId: string) => request<LearnUnit[]>(`/learning/journeys/${encodeURIComponent(journeyId)}/learn-units`),
  journeys: () => request<LearningJourney[]>("/learning/journeys"),
  journey: (id: string) => request<JourneyDetail>(`/learning/journeys/${id}`),
  startJourneyDraft: (input: CreateJourneyInput) => request<JourneyDraftStartResponse>("/learning/journey-drafts", {
    method: "POST",
    body: JSON.stringify(input),
  }),
  guideJourneyDraft: (runId: string, content: string) => request<JourneyDraftAck>(
    `/learning/journey-drafts/${encodeURIComponent(runId)}/guidance`, {
      method: "POST",
      body: JSON.stringify({content}),
    }),
  confirmJourneyDraft: (runId: string) => request<JourneyDraftAck>(
    `/learning/journey-drafts/${encodeURIComponent(runId)}/confirm`, {method: "POST"}),
  cancelJourneyDraft: (runId: string) => request<JourneyDraftAck>(
  `/learning/journey-drafts/${encodeURIComponent(runId)}/cancel`, {method: "POST"}),
  generationRunEventsUrl: (runId: string) =>
    `${API_BASE}/learning/generation-runs/${encodeURIComponent(runId)}/events`,
  cancelGenerationRun: (runId: string) => request<JourneyDraftAck>(
    `/learning/generation-runs/${encodeURIComponent(runId)}/cancel`, {method: "POST"}),
  startDiagnostic: (journeyId: string) => request<DiagnosticStartResponse>(
    `/learning/journeys/${encodeURIComponent(journeyId)}/diagnostic`, {method: "POST"}),
  assessment: (id: string) => request<AssessmentResponse>(`/learning/assessments/${id}`),
  startAssessment: (id: string) => request<AssessmentResponse>(`/learning/assessments/${id}/start`, {method: "POST"}),
  answer: (id: string, answer: { questionId: string; selectedOptionIds: string[]; submittedCode: string }) =>
    request<AssessmentResponse>(`/learning/assessments/${id}/answers`, {
      method: "POST",
      body: JSON.stringify(answer),
    }),
  submit: (id: string) => request<AssessmentSubmitResponse>(`/learning/assessments/${id}/submit`, {method: "POST"}),
  assessmentResult: (id: string) =>
    request<AssessmentResultResponse>(`/learning/assessments/${id}/result`),
  startLearnUnit: (journeyId: string, learnUnitCode: string) =>
    request<LearnUnitEntryResponse>(`/learning/journeys/${journeyId}/learn-units/${encodeURIComponent(learnUnitCode)}/start`, {method: "POST"}),
  continueLearnUnit: (journeyId: string, learnUnitCode: string) =>
    request<LearnUnitEntryResponse>(`/learning/journeys/${journeyId}/learn-units/${encodeURIComponent(learnUnitCode)}/continue`, {method: "POST"}),
  reviewLearnUnit: (journeyId: string, learnUnitCode: string) =>
    request<LearnUnitEntryResponse>(`/learning/journeys/${journeyId}/learn-units/${encodeURIComponent(learnUnitCode)}/review`, {method: "POST"}),
  learnUnitAssessment: (journeyId: string, learnUnitCode: string) =>
    request<AssessmentResponse>(`/learning/journeys/${journeyId}/learn-units/${encodeURIComponent(learnUnitCode)}/assessment`, {method: "POST"}),
  practiceLearnUnit: (journeyId: string, learnUnitCode: string) =>
    request<AssessmentResponse>(`/learning/journeys/${journeyId}/learn-units/${encodeURIComponent(learnUnitCode)}/practice`, {method: "POST"}),
  retryLearnUnit: (journeyId: string, learnUnitCode: string) =>
    request<AssessmentResponse>(`/learning/journeys/${journeyId}/learn-units/${encodeURIComponent(learnUnitCode)}/retry`, {method: "POST"}),
  chapterSynthesis: (journeyId: string, chapterCode: string) =>
    request<AssessmentResponse>(`/learning/journeys/${journeyId}/chapters/${encodeURIComponent(chapterCode)}/synthesis`, {method: "POST"}),
  retryChapterSynthesis: (journeyId: string, chapterCode: string) =>
    request<AssessmentResponse>(`/learning/journeys/${journeyId}/chapters/${encodeURIComponent(chapterCode)}/synthesis/retry`, {method: "POST"}),
  learnUnit: (journeyId: string, learnUnitCode: string) =>
    request<LearnUnitResponse>(`/learning/journeys/${journeyId}/learn-units/${encodeURIComponent(learnUnitCode)}`),
  advancePhase: (journeyId: string, learnUnitCode: string, phase: LearningPhase) =>
    request<LearnUnitResponse>(
      `/learning/journeys/${journeyId}/learn-units/${encodeURIComponent(learnUnitCode)}/phase/${phase}/advance`,
      {method: "POST"}),
  skipPhase: (journeyId: string, learnUnitCode: string, phase: LearningPhase) =>
    request<LearnUnitResponse>(
      `/learning/journeys/${journeyId}/learn-units/${encodeURIComponent(learnUnitCode)}/phase/${phase}/skip`,
      {method: "POST"}),
  guidedPractice: (journeyId: string, learnUnitCode: string, response: string) =>
    request<LearnUnitResponse>(
      `/learning/journeys/${journeyId}/learn-units/${encodeURIComponent(learnUnitCode)}/guided-practice`,
      {method: "POST", body: JSON.stringify({response})}),
  skipLearnUnit: (journeyId: string, learnUnitCode: string) =>
    request<LearnUnitResponse>(`/learning/journeys/${journeyId}/learn-units/${encodeURIComponent(learnUnitCode)}/skip`, {method: "POST"}),
  nextLearnUnit: (journeyId: string, learnUnitCode: string) =>
    request<JourneyDetail>(`/learning/journeys/${journeyId}/learn-units/${encodeURIComponent(learnUnitCode)}/next`, {method: "POST"}),
  tutor: (journeyId: string, learnUnitCode: string) =>
    request<TutorSessionResponse>(`/learning/journeys/${journeyId}/learn-units/${encodeURIComponent(learnUnitCode)}/tutor`, {method: "POST"}),
  session: (id: string) => request<SessionDetail>(`/sessions/${id}`),
    sendMessage: (id: string, content: string, questionContext: TutorQuestionContext | null = null) =>
    request<{ runId: string; messageId: string }>(`/sessions/${id}/messages`, {
      method: "POST",
        body: JSON.stringify({content, questionContext}),
    }),
  cancelRun: (sessionId: string, runId: string) =>
    request<{status: string}>(`/sessions/${sessionId}/runs/${runId}/cancel`, {method: "POST"}),
  eventsUrl: (id: string) => `${API_BASE}/sessions/${id}/events`,
  journeyDraftEventsUrl: (runId: string) => `${API_BASE}/learning/journey-drafts/${encodeURIComponent(runId)}/events`,
};
