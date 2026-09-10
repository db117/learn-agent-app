import type {FormEvent} from "react";
import type {JourneyDetail} from "../lib/api";

export type JourneyForm = {
    languageCode: string;
    goal: string;
    primaryLanguage: string;
    experienceYears: string;
    selfDescription: string;
    learningGoal: string;
};

type WelcomeViewProps = {
    journey: JourneyDetail | null;
    form: JourneyForm;
    busy: boolean;
    onFormChange: (field: keyof JourneyForm, value: string) => void;
    onCreateJourney: (event: FormEvent<HTMLFormElement>) => void | Promise<void>;
    onBeginDiagnostic: (journeyId: string) => void | Promise<void>;
    onNewJourney: () => void;
};

function statusLabel(status: string) {
    return status.toLowerCase().replaceAll("_", " ");
}

export function WelcomeView({
                                journey,
                                form,
                                busy,
                                onFormChange,
                                onCreateJourney,
                                onBeginDiagnostic,
                                onNewJourney,
                            }: WelcomeViewProps) {
    if (journey) {
        return (
            <section className="onboarding panel">
                <div className="section-kicker">RESUME JOURNEY</div>
                <h2>{journey.journey.goal}</h2>
                <p className="lead">你的 {journey.journey.languageCode} 学习 Journey 已保存。完成一次诊断后，系统会按
                    LearnUnit 前置关系生成路径。</p>
                <div className="resume-meta">
                    <span className="status-pill">{statusLabel(journey.journey.status)}</span>
                    <span>{journey.profile?.learningGoal || "尚未开始诊断"}</span>
                </div>
                <div className="button-row">
                    <button className="primary" onClick={() => void onBeginDiagnostic(journey.journey.id)}
                            disabled={busy}>继续诊断
                    </button>
                    <button className="secondary" onClick={onNewJourney} disabled={busy}>新建 Journey</button>
                </div>
            </section>
        );
    }

    return (
        <section className="onboarding panel">
            <div className="section-kicker">LEARNING JOURNEY · PHASE 2</div>
            <h2>从目标开始，走一条真正属于你的学习路径。</h2>
            <p className="lead">先提出你想学习的语言并填写背景。Agent 会按目标生成 LearnUnit
                教学内容和题目；诊断题集会固定保存，评分和路径由后端确定性规则负责。</p>
            <form onSubmit={(event) => void onCreateJourney(event)}>
                <div className="form-grid">
                    <label>学习语言
                        <input value={form.languageCode}
                               onChange={(event) => onFormChange("languageCode", event.target.value)}
                               placeholder="例如：Python、Rust、TypeScript" required/>
                    </label>
                    <label>你的主要语言
                        <input value={form.primaryLanguage}
                               onChange={(event) => onFormChange("primaryLanguage", event.target.value)} required/>
                    </label>
                    <label>相关经验（年）
                        <input type="number" min="0" max="100" value={form.experienceYears}
                               onChange={(event) => onFormChange("experienceYears", event.target.value)}/>
                    </label>
                    <label>Journey 名称
                        <input value={form.goal} onChange={(event) => onFormChange("goal", event.target.value)}
                               required/>
                    </label>
                </div>
                <label>你想达成什么
                    <textarea value={form.learningGoal}
                              onChange={(event) => onFormChange("learningGoal", event.target.value)} rows={3} required/>
                </label>
                <label>当前水平补充
                    <textarea value={form.selfDescription}
                              onChange={(event) => onFormChange("selfDescription", event.target.value)} rows={3}
                              placeholder="例如：有后端开发经验，希望系统掌握所选语言。"/>
                </label>
                {busy && <p className="generation-status" role="status">正在生成 Journey、LearnUnit
                    教学内容和适用题目，请稍候…</p>}
                <button className="primary wide" type="submit"
                        disabled={busy || !form.languageCode.trim()}>{busy ? "生成中…" : "创建 Journey 并开始诊断"}</button>
            </form>
        </section>
    );
}
