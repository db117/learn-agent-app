import {MarkdownMessage} from "../agent/MarkdownMessage";

export type LearningProgress = {
    journeyId: number;
    learningJourneyId: number;
    status: string;
    currentLearnUnitCode: string | null;
    currentLearnUnitTitle: string | null;
    currentLearnUnitObjective: string | null;
    currentLearnUnitContent: string | null;
    practiceVerified: boolean;
    completedCount: number;
    totalCount: number;
    chapters: LearningChapter[];
};

export type LearningChapter = {
    code: string;
    title: string;
    units: LearningUnitProgress[];
};

export type LearningUnitProgress = {
    code: string;
    title: string;
    status: "PENDING" | "CURRENT" | "COMPLETED" | "SKIPPED";
    practiceVerified: boolean;
};

type Props = {
    progress: LearningProgress | null;
    loading?: boolean;
    showPath?: boolean;
};

const stages = ["Explain", "Example", "Practice"];

type LessonSection = {
    title: string;
    content: string;
};

export function LearnModePanel({progress, loading = false, showPath = true}: Props) {
    if (loading) {
        return <section className="learn-panel" aria-labelledby="learn-mode-title">
            <p className="mode-label">LEARN MODE</p>
            <h3 id="learn-mode-title">正在加载当前课程…</h3>
        </section>;
    }

    if (!progress) return null;

    if (progress.status === "COMPLETED") {
        return <section className="learn-panel" aria-labelledby="learn-mode-title">
            <p className="mode-label">LEARN MODE</p>
            <h3 id="learn-mode-title">学习路径已完成</h3>
            <p className="learn-summary">已完成 {progress.completedCount} / {progress.totalCount} 个 LearnUnit。</p>
            {showPath && <ChapterPath chapters={progress.chapters}/>}
        </section>;
    }

    const stageComplete = [
        false,
        false,
        progress.practiceVerified,
    ];

    return <section className="learn-panel" aria-labelledby="learn-mode-title">
        <div className="learn-heading">
            <div>
                <p className="mode-label">LEARN MODE</p>
                <h3 id="learn-mode-title">{progress.currentLearnUnitTitle ?? progress.currentLearnUnitCode}</h3>
            </div>
            <span className="journey-status">{progress.completedCount} / {progress.totalCount}</span>
        </div>
        <ol className="learn-stages" aria-label="Learn Mode 学习阶段">
            {stages.map((stage, index) => (
                <li className={stageComplete[index] ? "completed" : ""} key={stage}>
                    <span aria-hidden="true">{stageComplete[index] ? "✓" : index + 1}</span>
                    {stage}
                </li>
            ))}
        </ol>
        {progress.currentLearnUnitObjective && (
            <p className="learn-objective"><strong>本单元目标：</strong>{progress.currentLearnUnitObjective}</p>
        )}
        {progress.currentLearnUnitContent && (
            <LessonContent content={progress.currentLearnUnitContent}/>
        )}
        {showPath && <ChapterPath chapters={progress.chapters}/>}
    </section>;
}

export function LearningPathPanel({progress, loading = false}: Omit<Props, "showPath">) {
    if (loading) {
        return <section className="learn-panel learning-path-panel" aria-label="章节学习路径">
            <p className="mode-label">COURSE PATH</p>
            <p className="session-status">正在加载课程路径…</p>
        </section>;
    }

    if (!progress) return null;

    return <section className="learn-panel learning-path-panel" aria-label="章节学习路径">
        <ChapterPath chapters={progress.chapters}/>
    </section>;
}

function LessonContent({content}: { content: string }) {
    const sections = splitLessonContent(content);
    return <section className="lesson-sections" aria-label="LearnUnit 教学内容">
        {sections.map((section) => (
            <article className="lesson-section" key={section.title}>
                <h4>{section.title}</h4>
                <div className="learn-content">
                    <MarkdownMessage text={section.content}/>
                </div>
            </article>
        ))}
    </section>;
}

function splitLessonContent(content: string): LessonSection[] {
    const headings = [...content.matchAll(/^##\s+(Concept|Example|Practice)\s*$/gm)];
    if (headings.length === 0) {
        return [{title: "课程内容", content: content.trim()}];
    }
    return headings.map((heading, index) => {
        const start = (heading.index ?? 0) + heading[0].length;
        const end = headings[index + 1]?.index ?? content.length;
        return {title: heading[1], content: content.slice(start, end).trim()};
    });
}

function ChapterPath({chapters}: { chapters: LearningChapter[] }) {
    if (chapters.length === 0) return null;
    return <section className="chapter-path" aria-label="章节学习路径">
        <h4>课程路径</h4>
        {chapters.map((chapter, chapterIndex) => (
            <article className="chapter-path-item" key={chapter.code}>
                <h5>第 {chapterIndex + 1} 章：{chapter.title}</h5>
                <ol>
                    {chapter.units.map((unit, unitIndex) => (
                        <li className={unit.status === "COMPLETED" ? "completed" : unit.status === "CURRENT" ? "current" : ""}
                            key={unit.code}>
                            <span aria-hidden="true">
                                {unit.status === "COMPLETED" ? "✓" : unit.status === "CURRENT" ? "▶" : unitIndex + 1}
                            </span>
                            <span>{unit.title}</span>
                            <small>{unit.status === "CURRENT" ? "当前学习" : unit.status === "COMPLETED" ? "已完成" : "待解锁"}</small>
                        </li>
                    ))}
                </ol>
            </article>
        ))}
    </section>;
}
