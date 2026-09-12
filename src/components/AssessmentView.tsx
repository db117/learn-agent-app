import Editor from "@monaco-editor/react";
import type {AssessmentResponse, Question} from "../lib/api";

export type AnswerDraft = { selectedOptionIds: string[]; submittedCode: string };
type QuestionOption = { id: string; text: string };
type QuestionConfig = { options: QuestionOption[]; multiple: boolean };
type Theme = "dark" | "light";

const MONACO_LANGUAGE_ALIASES: Record<string, string> = {
    "c#": "csharp",
    "c++": "cpp",
    csharp: "csharp",
    cs: "csharp",
    cpp: "cpp",
    css: "css",
    go: "go",
    golang: "go",
    html: "html",
    html5: "html",
    java: "java",
    javascript: "javascript",
    js: "javascript",
    json: "json",
    python: "python",
    py: "python",
    rust: "rust",
    rs: "rust",
    sql: "sql",
    typescript: "typescript",
    ts: "typescript",
};

export const emptyDraft: AnswerDraft = {selectedOptionIds: [], submittedCode: ""};

type AssessmentViewProps = {
    assessment: AssessmentResponse | null;
    currentQuestion: Question | null;
    questionIndex: number;
    currentDraft: AnswerDraft;
    learnUnitName: string;
    questionLearnUnitName: string;
    theme: Theme;
    busy: boolean;
    onSelectedOptionIds: (selectedOptionIds: string[]) => void;
    onCodeChange: (submittedCode: string) => void;
    onBack: () => void | Promise<void>;
    onNext: () => void | Promise<void>;
};

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

function monacoLanguage(language: string | null) {
    const normalized = language?.trim().toLowerCase().replace(/[\s_-]+/g, "") ?? "";
    return MONACO_LANGUAGE_ALIASES[normalized] ?? "plaintext";
}

export function AssessmentView({
                                   assessment,
                                   currentQuestion,
                                   questionIndex,
                                   currentDraft,
                                   learnUnitName,
                                   questionLearnUnitName,
                                   theme,
                                   busy,
                                   onSelectedOptionIds,
                                   onCodeChange,
                                   onBack,
                                   onNext,
                               }: AssessmentViewProps) {
    if (!assessment || !currentQuestion) return <p className="empty">正在准备题目…</p>;
    const diagnostic = assessment.assessment.type === "DIAGNOSTIC";
    const synthesis = assessment.assessment.type === "CHAPTER_SYNTHESIS";
    const questionConfig = parseQuestionConfig(currentQuestion.configJson);

    return (
        <section className="assessment panel">
            <div className="assessment-header">
                <div>
                    <div className="section-kicker">{diagnostic ? "DIAGNOSTIC" : synthesis ? "CHAPTER SYNTHESIS" : "LEARN UNIT CHECK"}</div>
                    <h2>{diagnostic ? "了解你的起点" : learnUnitName}</h2>
                </div>
                <span className="muted">{questionIndex + 1} / {assessment.questions.length}</span>
            </div>
            <div className="progress-bar"><span
                style={{width: `${((questionIndex + 1) / assessment.questions.length) * 100}%`}}/></div>
            <div className="question-card">
                <div className="question-meta">
                    <span>{currentQuestion.type === "CODING" ? "CODING" : "MULTIPLE CHOICE"}</span><span>{questionLearnUnitName} · {currentQuestion.points} pts</span>
                </div>
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
                                        onChange={() => {
                                            const previous = currentDraft.selectedOptionIds;
                                            const selectedOptionIds = questionConfig.multiple
                                                ? previous.includes(option.id) ? previous.filter((id) => id !== option.id) : [...previous, option.id]
                                                : [option.id];
                                            onSelectedOptionIds(selectedOptionIds);
                                        }}
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
                        <div className="coding-editor">
                            <Editor
                                height="320px"
                                language={monacoLanguage(currentQuestion.language)}
                                theme={theme === "dark" ? "vs-dark" : "light"}
                                value={currentDraft.submittedCode}
                                onChange={(value) => onCodeChange(value ?? "")}
                                options={{
                                    automaticLayout: true,
                                    minimap: {enabled: false},
                                    padding: {top: 12, bottom: 12},
                                    scrollBeyondLastLine: false,
                                    tabSize: 2,
                                    wordWrap: "on",
                                }}
                            />
                        </div>
                    </div>
                )}
            </div>
            <div className="assessment-actions">
                <button className="secondary" onClick={() => void onBack()}
                        disabled={busy || questionIndex === 0}>上一题
                </button>
                <span className="muted">答案会保存到 SQLite</span>
                <button className="primary" onClick={() => void onNext()}
                        disabled={busy}>{busy ? "保存中…" : questionIndex + 1 === assessment.questions.length ? "提交评估" : "保存并继续"}</button>
            </div>
        </section>
    );
}
