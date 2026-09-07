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

export type JourneyStatus = "ACTIVE" | "COMPLETED" | "ARCHIVED";
export type LearnerSkillStatus = "LOCKED" | "READY" | "LEARNING" | "ASSESSING" | "PASSED" | "SKIPPED";
export type LearningPathItemStatus = "PENDING" | "CURRENT" | "COMPLETED" | "SKIPPED";
export type QuestionType = "MULTIPLE_CHOICE" | "CODING";
export type AssessmentType = "DIAGNOSTIC" | "SKILL";
export type AssessmentStatus = "CREATED" | "IN_PROGRESS" | "COMPLETED";

export type LearningLanguage = {
  id: string;
  code: string;
  name: string;
  description: string;
  enabled: boolean;
};

export type LearningSkill = {
  id: string;
  languageCode: string;
  code: string;
  name: string;
  description: string;
  sequence: number;
  prerequisiteSkillCodes: string[];
  passScore: number;
  minCodingScore: number;
  enabled: boolean;
  learningObjectives: string[];
  lessonIntro: string;
  keyConcepts: string[];
  examples: string[];
  diagnosticEligible: boolean;
};

export type LearningJourney = {
  id: string;
  userId: string;
  languageCode: string;
  goal: string;
  status: JourneyStatus;
  createdAt: string;
  updatedAt: string;
  currentLearningSkillId: string | null;
};

export type LearnerProfile = {
  journeyId: string;
  primaryLanguage: string;
  experienceYears: number | null;
  selfDescription: string;
  learningGoal: string;
};

export type LearnerSkill = {
  journeyId: string;
  skillCode: string;
  status: LearnerSkillStatus;
  masteryScore: number;
  bestAssessmentScore: number;
  attemptCount: number;
  passReason: "DIAGNOSTIC" | "LEARNING" | null;
  startedAt: string | null;
  passedAt: string | null;
  skippedAt: string | null;
};

export type LearningPathItem = {
  id: string;
  journeyId: string;
  skillCode: string;
  sequence: number;
  status: LearningPathItemStatus;
};

export type LearningLesson = {
  skillCode: string;
  title: string;
  learningObjectives: string[];
  introContent: string;
  keyConcepts: string[];
  examples: string[];
};

export type Question = {
  id: string;
  skillCode: string;
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
  skillCode: string | null;
  type: AssessmentType;
  status: AssessmentStatus;
  createdAt: string;
  completedAt: string | null;
};

export type AssessmentAttempt = {
  id: string;
  assessmentId: string;
  journeyId: string;
  skillCode: string | null;
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

export type DiagnosticSkillResult = {
  skillCode: string;
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
  skillResults: DiagnosticSkillResult[];
  questionAttempts: QuestionAttempt[];
};

export type SkillResponse = {
  journeyId: string;
  skill: LearningSkill;
  learnerSkill: LearnerSkill | null;
  pathItem: LearningPathItem | null;
  lesson: LearningLesson;
  attempts: AssessmentAttempt[];
};

export type JourneyDetail = {
  journey: LearningJourney;
  profile: LearnerProfile | null;
  path: LearningPathItem[];
  learnerSkills: LearnerSkill[];
};

export type TutorSessionResponse = {
  session: SessionSummary;
  journeyId: string;
  skillCode: string;
};

export type CreateJourneyInput = {
  languageCode: string;
  goal: string;
  primaryLanguage: string;
  experienceYears: number | null;
  selfDescription: string;
  learningGoal: string;
};

const API_BASE = "http://127.0.0.1:18080/api";

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: {"Content-Type": "application/json"},
    ...init,
  });
  if (!response.ok) {
    const raw = await response.text();
    try {
      const parsed = JSON.parse(raw) as { error?: string };
      throw new Error(parsed.error || `${response.status} ${response.statusText}`);
    } catch (cause) {
      if (cause instanceof Error && cause.message !== raw) throw cause;
      throw new Error(raw || `${response.status} ${response.statusText}`);
    }
  }
  return response.json() as Promise<T>;
}

export const api = {
  health: () => request<BackendHealth>("/health"),
  languages: () => request<LearningLanguage[]>("/learning/languages"),
  journeySkills: (journeyId: string) => request<LearningSkill[]>(`/learning/journeys/${encodeURIComponent(journeyId)}/skills`),
  journeys: () => request<LearningJourney[]>("/learning/journeys"),
  journey: (id: string) => request<JourneyDetail>(`/learning/journeys/${id}`),
  createJourney: (input: CreateJourneyInput) => request<LearningJourney>("/learning/journeys", {
    method: "POST",
    body: JSON.stringify(input),
  }),
  diagnostic: (journeyId: string) => request<AssessmentResponse>(`/learning/journeys/${journeyId}/diagnostic`, {method: "POST"}),
  assessment: (id: string) => request<AssessmentResponse>(`/learning/assessments/${id}`),
  startAssessment: (id: string) => request<AssessmentResponse>(`/learning/assessments/${id}/start`, {method: "POST"}),
  answer: (id: string, answer: { questionId: string; selectedOptionIds: string[]; submittedCode: string }) =>
    request<AssessmentResponse>(`/learning/assessments/${id}/answers`, {
      method: "POST",
      body: JSON.stringify(answer),
    }),
  submit: (id: string) => request<AssessmentResultResponse>(`/learning/assessments/${id}/submit`, {method: "POST"}),
  startSkill: (journeyId: string, skillCode: string) =>
    request<SkillResponse>(`/learning/journeys/${journeyId}/skills/${encodeURIComponent(skillCode)}/start`, {method: "POST"}),
  skillAssessment: (journeyId: string, skillCode: string) =>
    request<AssessmentResponse>(`/learning/journeys/${journeyId}/skills/${encodeURIComponent(skillCode)}/assessment`, {method: "POST"}),
  skill: (journeyId: string, skillCode: string) =>
    request<SkillResponse>(`/learning/journeys/${journeyId}/skills/${encodeURIComponent(skillCode)}`),
  skipSkill: (journeyId: string, skillCode: string) =>
    request<SkillResponse>(`/learning/journeys/${journeyId}/skills/${encodeURIComponent(skillCode)}/skip`, {method: "POST"}),
  tutor: (journeyId: string, skillCode: string) =>
    request<TutorSessionResponse>(`/learning/journeys/${journeyId}/skills/${encodeURIComponent(skillCode)}/tutor`, {method: "POST"}),
  session: (id: string) => request<SessionDetail>(`/sessions/${id}`),
  sendMessage: (id: string, content: string) =>
    request<{ runId: string; messageId: string }>(`/sessions/${id}/messages`, {
      method: "POST",
      body: JSON.stringify({content}),
    }),
  eventsUrl: (id: string) => `${API_BASE}/sessions/${id}/events`,
};
