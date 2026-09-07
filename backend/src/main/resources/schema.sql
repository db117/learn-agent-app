-- Phase 1：会话主表，保存用户与一次 Tutor 对话的生命周期。
CREATE TABLE IF NOT EXISTS "session"
(
    id
    TEXT
    PRIMARY
    KEY,
    user_id
    TEXT
    NOT
    NULL,
    title
    TEXT
    NOT
    NULL,
    created_at
    TEXT
    NOT
    NULL,
    updated_at
    TEXT
    NOT
    NULL
);

-- Phase 1：会话消息表，保存用户和助手消息的展示内容。
CREATE TABLE IF NOT EXISTS message
(
    id
    TEXT
    PRIMARY
    KEY,
    session_id
    TEXT
    NOT
    NULL
    REFERENCES
    "session"
(
    id
),
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at TEXT NOT NULL
    );

-- Phase 1：Agent 运行记录，保存一次 Runner 执行的状态和错误。
CREATE TABLE IF NOT EXISTS agent_run
(
    id
    TEXT
    PRIMARY
    KEY,
    session_id
    TEXT
    NOT
    NULL
    REFERENCES
    "session"
(
    id
),
    status TEXT NOT NULL,
    error_message TEXT,
    started_at TEXT NOT NULL,
    completed_at TEXT
    );

-- Phase 1：ADK 事件流水表，sequence 用于按接收顺序恢复 SSE/事件流。
CREATE TABLE IF NOT EXISTS "event"
(
    sequence
    INTEGER
    PRIMARY
    KEY
    AUTOINCREMENT,
    id
    TEXT
    NOT
    NULL
    UNIQUE,
    session_id
    TEXT
    NOT
    NULL
    REFERENCES
    "session"
(
    id
),
    run_id TEXT NOT NULL,
    author TEXT NOT NULL,
    event_type TEXT NOT NULL,
    content TEXT NOT NULL,
    tool_call_json TEXT,
    tool_result_json TEXT,
    timestamp TEXT NOT NULL,
    raw_json TEXT NOT NULL
    );

-- Phase 1：应用配置表，保存需要跨重启保留的键值配置。
CREATE TABLE IF NOT EXISTS setting
(
    key
    TEXT
    PRIMARY
    KEY,
    value
    TEXT
    NOT
    NULL,
    updated_at
    TEXT
    NOT
    NULL
);

-- Phase 1 查询索引：按会话和时间读取消息。
CREATE INDEX IF NOT EXISTS message_session_idx ON message(session_id, created_at);
CREATE INDEX IF NOT EXISTS event_session_idx ON "event"(session_id, sequence);
CREATE INDEX IF NOT EXISTS run_session_idx ON agent_run(session_id, started_at);

-- 可学习语言目录；enabled=1 才允许新建 Journey。
CREATE TABLE IF NOT EXISTS learning_language
(
    id TEXT PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    description TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 0
);

-- 技能目录；前置关系和通过规则由课程数据定义。
CREATE TABLE IF NOT EXISTS learning_skill
(
    id TEXT PRIMARY KEY,
    language_code TEXT NOT NULL REFERENCES learning_language(code),
    code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    description TEXT NOT NULL,
    sequence INTEGER NOT NULL,
    prerequisite_skill_codes TEXT NOT NULL,
    pass_score INTEGER NOT NULL DEFAULT 80,
    min_coding_score INTEGER,
    enabled INTEGER NOT NULL DEFAULT 1,
    learning_objectives_json TEXT NOT NULL,
    lesson_intro TEXT NOT NULL,
    key_concepts_json TEXT NOT NULL,
    examples_json TEXT NOT NULL,
    diagnostic_eligible INTEGER NOT NULL DEFAULT 1
);

-- 学习 Journey 主记录；current_learning_skill_id 指向当前路径节点。
CREATE TABLE IF NOT EXISTS learning_journey
(
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    language_code TEXT NOT NULL REFERENCES learning_language(code),
    goal TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    current_learning_skill_id TEXT
);

-- Journey 专属课程关系；同一语言的不同 Journey 可以拥有不同的技能和 Lesson。
CREATE TABLE IF NOT EXISTS learning_journey_skill
(
    journey_id TEXT NOT NULL REFERENCES learning_journey(id),
    skill_code TEXT NOT NULL REFERENCES learning_skill(code),
    PRIMARY KEY (journey_id, skill_code)
);

-- 学习者背景资料，供诊断规划和 Tutor 上下文使用。
CREATE TABLE IF NOT EXISTS learner_profile
(
    journey_id TEXT PRIMARY KEY REFERENCES learning_journey(id),
    primary_language TEXT NOT NULL,
    experience_years INTEGER,
    self_description TEXT NOT NULL,
    learning_goal TEXT NOT NULL
);

-- Journey 中每项技能的状态、掌握度、历史最佳成绩和通过时间。
CREATE TABLE IF NOT EXISTS learner_skill
(
    journey_id TEXT NOT NULL REFERENCES learning_journey(id),
    skill_code TEXT NOT NULL REFERENCES learning_skill(code),
    status TEXT NOT NULL,
    mastery_score INTEGER NOT NULL DEFAULT 0,
    best_assessment_score INTEGER NOT NULL DEFAULT 0,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    pass_reason TEXT,
    started_at TEXT,
    passed_at TEXT,
    skipped_at TEXT,
    PRIMARY KEY (journey_id, skill_code)
);

-- Journey 的学习路径节点；已完成/跳过节点保留，便于恢复和审计。
CREATE TABLE IF NOT EXISTS learning_path_item
(
    id TEXT PRIMARY KEY,
    journey_id TEXT NOT NULL REFERENCES learning_journey(id),
    skill_code TEXT NOT NULL REFERENCES learning_skill(code),
    sequence INTEGER NOT NULL,
    status TEXT NOT NULL,
    UNIQUE (journey_id, skill_code)
);

-- 不可变题目定义；只能新增，不能更新，历史引用的题目通过下方表软删除。
CREATE TABLE IF NOT EXISTS question
(
    id TEXT PRIMARY KEY,
    skill_code TEXT NOT NULL REFERENCES learning_skill(code),
    type TEXT NOT NULL,
    difficulty INTEGER NOT NULL,
    prompt TEXT NOT NULL,
    points INTEGER NOT NULL,
    config_json TEXT,
    rubric_json TEXT,
    language TEXT,
    starter_code TEXT,
    reference_concepts_json TEXT,
    diagnostic_eligible INTEGER NOT NULL DEFAULT 0
);

-- 题目软删除标记；不物理删除 question，以保持历史 Attempt 可读。
CREATE TABLE IF NOT EXISTS question_retirement
(
    question_id TEXT PRIMARY KEY REFERENCES question(id),
    retired_at TEXT NOT NULL
);

-- 评估定义；具体题集由 assessment_question 固定下来。
CREATE TABLE IF NOT EXISTS assessment
(
    id TEXT PRIMARY KEY,
    journey_id TEXT NOT NULL REFERENCES learning_journey(id),
    skill_code TEXT REFERENCES learning_skill(code),
    type TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL,
    completed_at TEXT
);

-- 评估与题目的固定关联及题目顺序；创建后不随题库变化。
CREATE TABLE IF NOT EXISTS assessment_question
(
    assessment_id TEXT NOT NULL REFERENCES assessment(id),
    question_id TEXT NOT NULL REFERENCES question(id),
    sequence INTEGER NOT NULL,
    PRIMARY KEY (assessment_id, question_id)
);

-- 一次评估尝试；重试新增记录，不覆盖之前的评分历史。
CREATE TABLE IF NOT EXISTS assessment_attempt
(
    id TEXT PRIMARY KEY,
    assessment_id TEXT NOT NULL REFERENCES assessment(id),
    journey_id TEXT NOT NULL REFERENCES learning_journey(id),
    skill_code TEXT,
    attempt_number INTEGER NOT NULL,
    choice_score INTEGER,
    coding_score INTEGER,
    total_score INTEGER,
    passed INTEGER,
    started_at TEXT NOT NULL,
    completed_at TEXT
);

-- 评估中的单题答案、得分和反馈；进行中的评估可保存草稿答案。
CREATE TABLE IF NOT EXISTS question_attempt
(
    question_id TEXT NOT NULL REFERENCES question(id),
    assessment_attempt_id TEXT NOT NULL REFERENCES assessment_attempt(id),
    answer_json TEXT,
    score INTEGER,
    max_score INTEGER NOT NULL,
    feedback TEXT,
    correct INTEGER,
    submitted_code TEXT,
    evaluation_json TEXT,
    selected_option_ids_json TEXT,
    PRIMARY KEY (question_id, assessment_attempt_id)
);

-- Journey 技能到既有 ADK Session 的关联，保证同一技能复用 Tutor 会话。
CREATE TABLE IF NOT EXISTS tutor_session
(
    id TEXT PRIMARY KEY,
    journey_id TEXT NOT NULL REFERENCES learning_journey(id),
    skill_code TEXT NOT NULL REFERENCES learning_skill(code),
    session_id TEXT NOT NULL UNIQUE REFERENCES "session"(id),
    UNIQUE (journey_id, skill_code)
);

-- Learning 查询索引：按课程顺序、Journey、技能状态和题目可用性读取。
CREATE INDEX IF NOT EXISTS learning_skill_language_idx ON learning_skill(language_code, sequence);
CREATE INDEX IF NOT EXISTS journey_skill_journey_idx ON learning_journey_skill(journey_id, skill_code);
CREATE INDEX IF NOT EXISTS journey_user_idx ON learning_journey(user_id, updated_at);
CREATE INDEX IF NOT EXISTS learner_skill_journey_idx ON learner_skill(journey_id, status);
CREATE INDEX IF NOT EXISTS path_journey_idx ON learning_path_item(journey_id, sequence);
CREATE INDEX IF NOT EXISTS question_skill_idx ON question(skill_code, diagnostic_eligible);
CREATE INDEX IF NOT EXISTS question_retirement_idx ON question_retirement(retired_at);
CREATE INDEX IF NOT EXISTS assessment_journey_idx ON assessment(journey_id, created_at);
CREATE INDEX IF NOT EXISTS attempt_skill_idx ON assessment_attempt(journey_id, skill_code, completed_at);
