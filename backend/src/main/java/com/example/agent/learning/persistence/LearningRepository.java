package com.example.agent.learning.persistence;

import com.example.agent.learning.assessment.Assessment;
import com.example.agent.learning.assessment.AssessmentAttempt;
import com.example.agent.learning.assessment.AssessmentStatus;
import com.example.agent.learning.assessment.AssessmentType;
import com.example.agent.learning.assessment.Question;
import com.example.agent.learning.assessment.QuestionAttempt;
import com.example.agent.learning.assessment.QuestionType;
import com.example.agent.learning.catalog.LearningLanguage;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.journey.JourneyStatus;
import com.example.agent.learning.journey.LearnerProfile;
import com.example.agent.learning.journey.LearnerLearnUnit;
import com.example.agent.learning.journey.LearnerLearnUnitStatus;
import com.example.agent.learning.journey.LearningJourney;
import com.example.agent.learning.journey.PassReason;
import com.example.agent.learning.path.LearningPathItem;
import com.example.agent.learning.path.LearningPathItemStatus;
import com.example.agent.learning.workflow.WorkflowTransition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Learning Core 的 SQLite 数据访问层。
 *
 * <p>这里统一使用 Spring {@link JdbcClient}，不引入 JPA。Question 定义是
 * insert-only；退役通过 {@code question_retirement} 标记，保证已完成评估的历史
 * 仍然可以按原题读取。</p>
 */
@Repository
public class LearningRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcClient jdbc;

    public LearningRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入 LLM 生成的语言和 LearnUnit 目录。
     *
     * <p>这里使用 insert-only，避免后续模型响应覆盖已经持久化的课程定义。题目另由
     * {@link #insertGeneratedQuestion(Question)} 写入；Journey 专属 LearnUnit 由关联方法绑定。</p>
     */
    @Transactional
    public void insertGeneratedCatalog(List<LearningLanguage> languages, List<LearnUnit> learnUnits) {
        for (LearningLanguage language : languages) {
            jdbc.sql("""
                            INSERT INTO learning_language (id, code, name, description, enabled)
                            VALUES (:id, :code, :name, :description, :enabled)
                            ON CONFLICT(code) DO NOTHING
                            """)
                    .param("id", language.id())
                    .param("code", language.code())
                    .param("name", language.name())
                    .param("description", language.description())
                    .param("enabled", language.enabled() ? 1 : 0)
                    .update();
        }
        for (LearnUnit learnUnit : learnUnits) {
            jdbc.sql("""
                            INSERT INTO learn_unit
                              (id, language_code, code, name, description, sequence,
                               prerequisite_learn_unit_codes, pass_score, min_coding_score, enabled,
                               learning_objectives_json, lesson_intro, key_concepts_json, examples_json,
                               diagnostic_eligible)
                            VALUES (:id, :languageCode, :code, :name, :description, :sequence,
                              :prerequisites, :passScore, :minCodingScore, :enabled,
                              :objectives, :intro, :concepts, :examples, :diagnosticEligible)
                            ON CONFLICT(code) DO NOTHING
                            """)
                    .param("id", learnUnit.id())
                    .param("languageCode", learnUnit.languageCode())
                    .param("code", learnUnit.code())
                    .param("name", learnUnit.name())
                    .param("description", learnUnit.description())
                    .param("sequence", learnUnit.sequence())
                    .param("prerequisites", json(learnUnit.prerequisiteLearnUnitCodes()))
                    .param("passScore", learnUnit.passScore())
                    .param("minCodingScore", learnUnit.minCodingScore())
                    .param("enabled", learnUnit.enabled() ? 1 : 0)
                    .param("objectives", json(learnUnit.learningObjectives()))
                    .param("intro", learnUnit.lessonIntro())
                    .param("concepts", json(learnUnit.keyConcepts()))
                    .param("examples", json(learnUnit.examples()))
                    .param("diagnosticEligible", learnUnit.diagnosticEligible() ? 1 : 0)
                    .update();
        }
    }

    /** 将生成的 LearnUnit 绑定到一个 Journey；同语言的其他 Journey 不会看到这些内容。 */
    @Transactional
    public void insertGeneratedCatalogForJourney(
            String journeyId, List<LearningLanguage> languages, List<LearnUnit> learnUnits) {
        insertGeneratedCatalog(languages, learnUnits);
        for (LearnUnit learnUnit : learnUnits) {
            jdbc.sql("""
                            INSERT INTO learning_journey_learn_unit (journey_id, learn_unit_code)
                            VALUES (:journeyId, :learnUnitCode)
                            ON CONFLICT(journey_id, learn_unit_code) DO NOTHING
                            """)
                    .param("journeyId", journeyId)
                    .param("learnUnitCode", learnUnit.code())
                    .update();
        }
    }

    /** 查询所有启用的学习语言。 */
    public List<LearningLanguage> listLanguages() {
        return jdbc.sql("SELECT id, code, name, description, enabled FROM learning_language WHERE enabled = 1 ORDER BY name")
                .query((rs, rowNum) -> new LearningLanguage(
                        rs.getString("id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("description"), rs.getInt("enabled") != 0))
                .list();
    }

    /** 按编码查询语言，包括已禁用项，供业务层给出准确错误。 */
    public Optional<LearningLanguage> findLanguage(String code) {
        return jdbc.sql("SELECT id, code, name, description, enabled FROM learning_language WHERE code = :code")
                .param("code", code)
                .query((rs, rowNum) -> new LearningLanguage(
                        rs.getString("id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("description"), rs.getInt("enabled") != 0))
                .optional();
    }

    /** 查询一个 Journey 自己的 LearnUnit；不同 Journey 的同语言课程不会混用。 */
    public List<LearnUnit> listLearnUnitsForJourney(String journeyId) {
        return jdbc.sql("""
                        SELECT s.id, s.language_code, s.code, s.name, s.description, s.sequence,
                          s.prerequisite_learn_unit_codes, s.pass_score, s.min_coding_score, s.enabled,
                          s.learning_objectives_json, s.lesson_intro, s.key_concepts_json, s.examples_json,
                          s.diagnostic_eligible
                        FROM learn_unit s
                        JOIN learning_journey_learn_unit js ON js.learn_unit_code = s.code
                        WHERE js.journey_id = :journeyId AND s.enabled = 1
                        ORDER BY s.sequence, s.code
                        """)
                .param("journeyId", journeyId)
                .query((rs, rowNum) -> mapLearnUnit(rs))
                .list();
    }

    /** 按业务编码查询 LearnUnit。 */
    public Optional<LearnUnit> findLearnUnit(String code) {
        return jdbc.sql("""
                        SELECT id, language_code, code, name, description, sequence,
                          prerequisite_learn_unit_codes, pass_score, min_coding_score, enabled,
                          learning_objectives_json, lesson_intro, key_concepts_json, examples_json,
                          diagnostic_eligible
                        FROM learn_unit WHERE code = :code
                        """)
                .param("code", code)
                .query((rs, rowNum) -> mapLearnUnit(rs))
                .optional();
    }

    /** 查询指定 LearnUnit 的活动题目；已 soft delete 的题目不会出现在新评估中。 */
    public List<Question> listQuestionsForLearnUnit(String learnUnitCode) {
        return jdbc.sql("""
                        SELECT id, learn_unit_code, type, difficulty, prompt, points, config_json, rubric_json,
                          language, starter_code, reference_concepts_json, diagnostic_eligible
                        FROM question
                        WHERE learn_unit_code = :learnUnitCode
                          AND NOT EXISTS (SELECT 1 FROM question_retirement r WHERE r.question_id = question.id)
                        ORDER BY id
                        """)
                .param("learnUnitCode", learnUnitCode)
                .query((rs, rowNum) -> mapQuestion(rs))
                .list();
    }

    /** 查询一个 Journey 课程中的活动诊断题，避免混入同语言其他 Journey 的题目。 */
    public List<Question> listDiagnosticQuestionsForJourney(String journeyId) {
        return jdbc.sql("""
                        SELECT q.id, q.learn_unit_code, q.type, q.difficulty, q.prompt, q.points, q.config_json,
                          q.rubric_json, q.language, q.starter_code, q.reference_concepts_json, q.diagnostic_eligible
                        FROM question q
                        JOIN learning_journey_learn_unit js ON js.learn_unit_code = q.learn_unit_code
                        JOIN learn_unit s ON s.code = q.learn_unit_code
                        WHERE js.journey_id = :journeyId AND q.diagnostic_eligible = 1 AND s.enabled = 1
                          AND NOT EXISTS (SELECT 1 FROM question_retirement r WHERE r.question_id = q.id)
                        ORDER BY s.sequence, q.id
                        """)
                .param("journeyId", journeyId)
                .query((rs, rowNum) -> mapQuestion(rs))
                .list();
    }

    /** 插入 LLM 生成的新题；已有题目 ID 不允许覆盖。 */
    public void insertGeneratedQuestion(Question question) {
        jdbc.sql("""
                        INSERT OR IGNORE INTO question
                          (id, learn_unit_code, type, difficulty, prompt, points, config_json, rubric_json,
                           language, starter_code, reference_concepts_json, diagnostic_eligible)
                        VALUES (:id, :learnUnitCode, :type, :difficulty, :prompt, :points, :configJson, :rubricJson,
                          :language, :starterCode, :referenceConcepts, :diagnosticEligible)
                        """)
                .param("id", question.id())
                .param("learnUnitCode", question.learnUnitCode())
                .param("type", question.type().name())
                .param("difficulty", question.difficulty())
                .param("prompt", question.prompt())
                .param("points", question.points())
                .param("configJson", question.configJson())
                .param("rubricJson", question.rubricJson())
                .param("language", question.language())
                .param("starterCode", question.starterCode())
                .param("referenceConcepts", question.referenceConceptsJson())
                .param("diagnosticEligible", question.diagnosticEligible() ? 1 : 0)
                .update();
    }

    /** 退役题目而不物理删除，以保留历史 Assessment/Attempt 的外键引用。 */
    public void retireQuestion(String questionId) {
        jdbc.sql("""
                        INSERT OR IGNORE INTO question_retirement (question_id, retired_at)
                        VALUES (:questionId, :retiredAt)
                        """)
                .param("questionId", questionId)
                .param("retiredAt", Instant.now().toString())
                .update();
    }

    /** 新增一个 Journey 主记录。 */
    public void insertJourney(LearningJourney journey) {
        jdbc.sql("""
                        INSERT INTO learning_journey
                          (id, user_id, language_code, goal, status, created_at, updated_at, current_learn_unit_code)
                        VALUES (:id, :userId, :languageCode, :goal, :status, :createdAt, :updatedAt, :currentLearnUnit)
                        """)
                .param("id", journey.id())
                .param("userId", journey.userId())
                .param("languageCode", journey.languageCode())
                .param("goal", journey.goal())
                .param("status", journey.status().name())
                .param("createdAt", journey.createdAt().toString())
                .param("updatedAt", journey.updatedAt().toString())
                .param("currentLearnUnit", journey.currentLearnUnitCode())
                .update();
    }

    /** 查询本地用户的 Journey，最近更新的排在前面。 */
    public List<LearningJourney> listJourneys(String userId) {
        return jdbc.sql("""
                        SELECT id, user_id, language_code, goal, status, created_at, updated_at, current_learn_unit_code
                        FROM learning_journey WHERE user_id = :userId ORDER BY updated_at DESC
                        """)
                .param("userId", userId)
                .query((rs, rowNum) -> mapJourney(rs))
                .list();
    }

    /** 按 Journey 主键查询。 */
    public Optional<LearningJourney> findJourney(String id) {
        return jdbc.sql("""
                        SELECT id, user_id, language_code, goal, status, created_at, updated_at, current_learn_unit_code
                        FROM learning_journey WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, rowNum) -> mapJourney(rs))
                .optional();
    }

    /** 更新 Journey 状态和当前 LearnUnit 指针。 */
    public void updateJourney(String id, JourneyStatus status, String currentLearnUnit, Instant updatedAt) {
        jdbc.sql("""
                        UPDATE learning_journey SET status = :status, current_learn_unit_code = :currentLearnUnit,
                          updated_at = :updatedAt WHERE id = :id
                        """)
                .param("id", id)
                .param("status", status.name())
                .param("currentLearnUnit", currentLearnUnit)
                .param("updatedAt", updatedAt.toString())
                .update();
    }

    /** 保存或更新 Journey 的学习者背景。 */
    public void saveProfile(LearnerProfile profile) {
        jdbc.sql("""
                        INSERT INTO learner_profile (journey_id, primary_language, experience_years, self_description, learning_goal)
                        VALUES (:journeyId, :primaryLanguage, :experienceYears, :selfDescription, :learningGoal)
                        ON CONFLICT(journey_id) DO UPDATE SET primary_language = excluded.primary_language,
                          experience_years = excluded.experience_years, self_description = excluded.self_description,
                          learning_goal = excluded.learning_goal
                        """)
                .param("journeyId", profile.journeyId())
                .param("primaryLanguage", profile.primaryLanguage())
                .param("experienceYears", profile.experienceYears())
                .param("selfDescription", profile.selfDescription())
                .param("learningGoal", profile.learningGoal())
                .update();
    }

    /** 查询 Journey 的学习者背景。 */
    public Optional<LearnerProfile> findProfile(String journeyId) {
        return jdbc.sql("""
                        SELECT journey_id, primary_language, experience_years, self_description, learning_goal
                        FROM learner_profile WHERE journey_id = :journeyId
                        """)
                .param("journeyId", journeyId)
                .query((rs, rowNum) -> new LearnerProfile(
                        rs.getString("journey_id"), rs.getString("primary_language"),
                        nullableInt(rs.getObject("experience_years")), rs.getString("self_description"),
                        rs.getString("learning_goal")))
                .optional();
    }

    /** 查询 Journey 已产生状态记录的 LearnUnit。 */
    public List<LearnerLearnUnit> listLearnerLearnUnits(String journeyId) {
        return jdbc.sql("""
                        SELECT journey_id, learn_unit_code, status, mastery_score, best_assessment_score, attempt_count,
                          pass_reason, started_at, passed_at, skipped_at
                        FROM learner_learn_unit WHERE journey_id = :journeyId ORDER BY learn_unit_code
                        """)
                .param("journeyId", journeyId)
                .query((rs, rowNum) -> mapLearnerLearnUnit(rs))
                .list();
    }

    /** 查询单个 LearnUnit 状态；尚未被评估的 LearnUnit 没有记录。 */
    public Optional<LearnerLearnUnit> findLearnerLearnUnit(String journeyId, String learnUnitCode) {
        return jdbc.sql("""
                        SELECT journey_id, learn_unit_code, status, mastery_score, best_assessment_score, attempt_count,
                          pass_reason, started_at, passed_at, skipped_at
                        FROM learner_learn_unit WHERE journey_id = :journeyId AND learn_unit_code = :learnUnitCode
                        """)
                .param("journeyId", journeyId)
                .param("learnUnitCode", learnUnitCode)
                .query((rs, rowNum) -> mapLearnerLearnUnit(rs))
                .optional();
    }

    /** 保存 LearnUnit 状态，同时保留掌握度和历史最佳成绩。 */
    public void upsertLearnerLearnUnit(LearnerLearnUnit learnUnit) {
        jdbc.sql("""
                        INSERT INTO learner_learn_unit
                          (journey_id, learn_unit_code, status, mastery_score, best_assessment_score, attempt_count,
                           pass_reason, started_at, passed_at, skipped_at)
                        VALUES (:journeyId, :learnUnitCode, :status, :masteryScore, :bestScore, :attemptCount,
                          :passReason, :startedAt, :passedAt, :skippedAt)
                        ON CONFLICT(journey_id, learn_unit_code) DO UPDATE SET status = excluded.status,
                          mastery_score = excluded.mastery_score, best_assessment_score = excluded.best_assessment_score,
                          attempt_count = excluded.attempt_count, pass_reason = excluded.pass_reason,
                          started_at = excluded.started_at, passed_at = excluded.passed_at, skipped_at = excluded.skipped_at
                        """)
                .param("journeyId", learnUnit.journeyId())
                .param("learnUnitCode", learnUnit.learnUnitCode())
                .param("status", learnUnit.status().name())
                .param("masteryScore", learnUnit.masteryScore())
                .param("bestScore", learnUnit.bestAssessmentScore())
                .param("attemptCount", learnUnit.attemptCount())
                .param("passReason", learnUnit.passReason() == null ? null : learnUnit.passReason().name())
                .param("startedAt", instant(learnUnit.startedAt()))
                .param("passedAt", instant(learnUnit.passedAt()))
                .param("skippedAt", instant(learnUnit.skippedAt()))
                .update();
    }

    /** 用一次事务替换 Journey 的完整 Path。 */
    @Transactional
    public void replacePath(String journeyId, List<LearningPathItem> items) {
        jdbc.sql("DELETE FROM learning_path_item WHERE journey_id = :journeyId")
                .param("journeyId", journeyId)
                .update();
        for (LearningPathItem item : items) {
            insertPathItem(item);
        }
    }

    private void insertPathItem(LearningPathItem item) {
        jdbc.sql("""
                        INSERT INTO learning_path_item (id, journey_id, learn_unit_code, sequence, status)
                        VALUES (:id, :journeyId, :learnUnitCode, :sequence, :status)
                        """)
                .param("id", item.id())
                .param("journeyId", item.journeyId())
                .param("learnUnitCode", item.learnUnitCode())
                .param("sequence", item.sequence())
                .param("status", item.status().name())
                .update();
    }

    /** 查询完整 Path，包括已完成和已跳过的历史节点。 */
    public List<LearningPathItem> listPath(String journeyId) {
        return jdbc.sql("""
                        SELECT id, journey_id, learn_unit_code, sequence, status
                        FROM learning_path_item WHERE journey_id = :journeyId ORDER BY sequence
                        """)
                .param("journeyId", journeyId)
                .query((rs, rowNum) -> new LearningPathItem(
                        rs.getString("id"), rs.getString("journey_id"), rs.getString("learn_unit_code"),
                        rs.getInt("sequence"), LearningPathItemStatus.valueOf(rs.getString("status"))))
                .list();
    }

    /** 查询 Journey 中某 LearnUnit 对应的 Path 节点。 */
    public Optional<LearningPathItem> findPathItem(String journeyId, String learnUnitCode) {
        return jdbc.sql("""
                        SELECT id, journey_id, learn_unit_code, sequence, status
                        FROM learning_path_item WHERE journey_id = :journeyId AND learn_unit_code = :learnUnitCode
                        """)
                .param("journeyId", journeyId)
                .param("learnUnitCode", learnUnitCode)
                .query((rs, rowNum) -> new LearningPathItem(
                        rs.getString("id"), rs.getString("journey_id"), rs.getString("learn_unit_code"),
                        rs.getInt("sequence"), LearningPathItemStatus.valueOf(rs.getString("status"))))
                .optional();
    }

    /** 清理旧的 CURRENT 标记，保证一个 Journey 最多只有一个当前节点。 */
    public void resetCurrentPathItems(String journeyId) {
        jdbc.sql("UPDATE learning_path_item SET status = 'PENDING' WHERE journey_id = :journeyId AND status = 'CURRENT'")
                .param("journeyId", journeyId)
                .update();
    }

    /** 更新一个 Path 节点状态。 */
    public void updatePathItem(String journeyId, String learnUnitCode, LearningPathItemStatus status) {
        jdbc.sql("UPDATE learning_path_item SET status = :status WHERE journey_id = :journeyId AND learn_unit_code = :learnUnitCode")
                .param("journeyId", journeyId)
                .param("learnUnitCode", learnUnitCode)
                .param("status", status.name())
                .update();
    }

    /** 新增 Assessment 定义；题目关联由 insertAssessmentQuestion 单独写入。 */
    public void insertAssessment(Assessment assessment) {
        jdbc.sql("""
                        INSERT INTO assessment (id, journey_id, learn_unit_code, type, status, created_at, completed_at)
                        VALUES (:id, :journeyId, :learnUnitCode, :type, :status, :createdAt, :completedAt)
                        """)
                .param("id", assessment.id())
                .param("journeyId", assessment.journeyId())
                .param("learnUnitCode", assessment.learnUnitCode())
                .param("type", assessment.type().name())
                .param("status", assessment.status().name())
                .param("createdAt", assessment.createdAt().toString())
                .param("completedAt", instant(assessment.completedAt()))
                .update();
    }

    /** 按 Assessment 主键查询评估定义。 */
    public Optional<Assessment> findAssessment(String id) {
        return jdbc.sql("""
                        SELECT id, journey_id, learn_unit_code, type, status, created_at, completed_at
                        FROM assessment WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, rowNum) -> mapAssessment(rs))
                .optional();
    }

    /** 查询 Journey 最近的一份诊断，确保重启和 Retry 使用固定题集。 */
    public Optional<Assessment> findDiagnosticAssessment(String journeyId) {
        return jdbc.sql("""
                        SELECT id, journey_id, learn_unit_code, type, status, created_at, completed_at
                        FROM assessment
                        WHERE journey_id = :journeyId AND type = 'DIAGNOSTIC'
                        ORDER BY created_at DESC LIMIT 1
                        """)
                .param("journeyId", journeyId)
                .query((rs, rowNum) -> mapAssessment(rs))
                .optional();
    }

    /** 查询某 LearnUnit 最近的一份评估，Retry 沿用该 Assessment 的题集。 */
    public Optional<Assessment> findLatestLearnUnitAssessment(String journeyId, String learnUnitCode) {
        return jdbc.sql("""
                        SELECT id, journey_id, learn_unit_code, type, status, created_at, completed_at
                        FROM assessment
                        WHERE journey_id = :journeyId AND learn_unit_code = :learnUnitCode AND type = 'LEARN_UNIT'
                        ORDER BY created_at DESC LIMIT 1
                        """)
                .param("journeyId", journeyId)
                .param("learnUnitCode", learnUnitCode)
                .query((rs, rowNum) -> mapAssessment(rs))
                .optional();
    }

    /** 更新评估生命周期，不修改其固定题集。 */
    public void updateAssessment(String id, AssessmentStatus status, Instant completedAt) {
        jdbc.sql("UPDATE assessment SET status = :status, completed_at = :completedAt WHERE id = :id")
                .param("id", id)
                .param("status", status.name())
                .param("completedAt", instant(completedAt))
                .update();
    }

    /** 固定 Assessment 与 Question 的关联及题目顺序。 */
    public void insertAssessmentQuestion(String assessmentId, String questionId, int sequence) {
        jdbc.sql("""
                        INSERT OR IGNORE INTO assessment_question (assessment_id, question_id, sequence)
                        VALUES (:assessmentId, :questionId, :sequence)
                        """)
                .param("assessmentId", assessmentId)
                .param("questionId", questionId)
                .param("sequence", sequence)
                .update();
    }

    /** 查询 Assessment 的固定题集；故意包含已退役题目以支持历史读取。 */
    public List<Question> listQuestionsForAssessment(String assessmentId) {
        return jdbc.sql("""
                        SELECT q.id, q.learn_unit_code, q.type, q.difficulty, q.prompt, q.points, q.config_json,
                          q.rubric_json, q.language, q.starter_code, q.reference_concepts_json, q.diagnostic_eligible
                        FROM assessment_question aq JOIN question q ON q.id = aq.question_id
                        WHERE aq.assessment_id = :assessmentId ORDER BY aq.sequence
                        """)
                .param("assessmentId", assessmentId)
                .query((rs, rowNum) -> mapQuestion(rs))
                .list();
    }

    /** 新增一次 Attempt，旧 Attempt 不被更新或删除。 */
    public void insertAttempt(AssessmentAttempt attempt) {
        jdbc.sql("""
                        INSERT INTO assessment_attempt
                          (id, assessment_id, journey_id, learn_unit_code, attempt_number, choice_score, coding_score,
                           total_score, passed, started_at, completed_at)
                        VALUES (:id, :assessmentId, :journeyId, :learnUnitCode, :attemptNumber, :choiceScore, :codingScore,
                          :totalScore, :passed, :startedAt, :completedAt)
                        """)
                .param("id", attempt.id())
                .param("assessmentId", attempt.assessmentId())
                .param("journeyId", attempt.journeyId())
                .param("learnUnitCode", attempt.learnUnitCode())
                .param("attemptNumber", attempt.attemptNumber())
                .param("choiceScore", attempt.choiceScore())
                .param("codingScore", attempt.codingScore())
                .param("totalScore", attempt.totalScore())
                .param("passed", attempt.passed() == null ? null : (attempt.passed() ? 1 : 0))
                .param("startedAt", attempt.startedAt().toString())
                .param("completedAt", instant(attempt.completedAt()))
                .update();
    }

    /** 只在提交时补齐 Attempt 的分数、通过结果和完成时间。 */
    public void updateAttempt(String id, Integer choiceScore, Integer codingScore, Integer totalScore, Boolean passed, Instant completedAt) {
        jdbc.sql("""
                        UPDATE assessment_attempt SET choice_score = :choiceScore, coding_score = :codingScore,
                          total_score = :totalScore, passed = :passed, completed_at = :completedAt WHERE id = :id
                        """)
                .param("id", id)
                .param("choiceScore", choiceScore)
                .param("codingScore", codingScore)
                .param("totalScore", totalScore)
                .param("passed", passed == null ? null : (passed ? 1 : 0))
                .param("completedAt", instant(completedAt))
                .update();
    }

    /** 查询当前未提交 Attempt，用于继续答题或恢复页面。 */
    public Optional<AssessmentAttempt> findOpenAttempt(String assessmentId) {
        return jdbc.sql("""
                        SELECT id, assessment_id, journey_id, learn_unit_code, attempt_number, choice_score, coding_score,
                          total_score, passed, started_at, completed_at
                        FROM assessment_attempt WHERE assessment_id = :assessmentId AND completed_at IS NULL
                        ORDER BY attempt_number DESC LIMIT 1
                        """)
                .param("assessmentId", assessmentId)
                .query((rs, rowNum) -> mapAttempt(rs))
                .optional();
    }

    /** 按 Attempt 主键查询。 */
    public Optional<AssessmentAttempt> findAttempt(String id) {
        return jdbc.sql("""
                        SELECT id, assessment_id, journey_id, learn_unit_code, attempt_number, choice_score, coding_score,
                          total_score, passed, started_at, completed_at
                        FROM assessment_attempt WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, rowNum) -> mapAttempt(rs))
                .optional();
    }

    /** 根据历史最大序号生成下一次 Retry 序号。 */
    public int nextAttemptNumber(String assessmentId) {
        Number value = jdbc.sql("SELECT COALESCE(MAX(attempt_number), 0) + 1 FROM assessment_attempt WHERE assessment_id = :assessmentId")
                .param("assessmentId", assessmentId)
                .query(Number.class)
                .single();
        return value.intValue();
    }

    /** 查询一个 Assessment 的全部尝试记录。 */
    public List<AssessmentAttempt> listAttemptsForAssessment(String assessmentId) {
        return jdbc.sql("""
                        SELECT id, assessment_id, journey_id, learn_unit_code, attempt_number, choice_score, coding_score,
                          total_score, passed, started_at, completed_at
                        FROM assessment_attempt WHERE assessment_id = :assessmentId ORDER BY attempt_number DESC
                        """)
                .param("assessmentId", assessmentId)
                .query((rs, rowNum) -> mapAttempt(rs))
                .list();
    }

    /** 查询某 Journey LearnUnit 的全部评估尝试。 */
    public List<AssessmentAttempt> listAttemptsForLearnUnit(String journeyId, String learnUnitCode) {
        return jdbc.sql("""
                        SELECT id, assessment_id, journey_id, learn_unit_code, attempt_number, choice_score, coding_score,
                          total_score, passed, started_at, completed_at
                        FROM assessment_attempt WHERE journey_id = :journeyId AND learn_unit_code = :learnUnitCode
                        ORDER BY started_at DESC
                        """)
                .param("journeyId", journeyId)
                .param("learnUnitCode", learnUnitCode)
                .query((rs, rowNum) -> mapAttempt(rs))
                .list();
    }

    /** 保存逐题答案；进行中可以覆盖草稿，已完成记录由业务层不再修改。 */
    public void saveQuestionAttempt(QuestionAttempt attempt) {
        jdbc.sql("""
                        INSERT INTO question_attempt
                          (question_id, assessment_attempt_id, answer_json, score, max_score, feedback, correct,
                           submitted_code, evaluation_json, selected_option_ids_json)
                        VALUES (:questionId, :attemptId, :answerJson, :score, :maxScore, :feedback, :correct,
                          :submittedCode, :evaluationJson, :selectedOptionIds)
                        ON CONFLICT(question_id, assessment_attempt_id) DO UPDATE SET answer_json = excluded.answer_json,
                          score = excluded.score, max_score = excluded.max_score, feedback = excluded.feedback,
                          correct = excluded.correct, submitted_code = excluded.submitted_code,
                          evaluation_json = excluded.evaluation_json, selected_option_ids_json = excluded.selected_option_ids_json
                        """)
                .param("questionId", attempt.questionId())
                .param("attemptId", attempt.assessmentAttemptId())
                .param("answerJson", attempt.answerJson())
                .param("score", attempt.score())
                .param("maxScore", attempt.maxScore())
                .param("feedback", attempt.feedback())
                .param("correct", attempt.correct() == null ? null : (attempt.correct() ? 1 : 0))
                .param("submittedCode", attempt.submittedCode())
                .param("evaluationJson", attempt.evaluationJson())
                .param("selectedOptionIds", attempt.selectedOptionIdsJson())
                .update();
    }

    /** 查询一次 Attempt 的全部逐题答案。 */
    public List<QuestionAttempt> listQuestionAttempts(String assessmentAttemptId) {
        return jdbc.sql("""
                        SELECT question_id, assessment_attempt_id, answer_json, score, max_score, feedback, correct,
                          submitted_code, evaluation_json, selected_option_ids_json
                        FROM question_attempt WHERE assessment_attempt_id = :attemptId ORDER BY rowid
                        """)
                .param("attemptId", assessmentAttemptId)
                .query((rs, rowNum) -> new QuestionAttempt(
                        rs.getString("question_id"), rs.getString("assessment_attempt_id"), rs.getString("answer_json"),
                        nullableInt(rs.getObject("score")), rs.getInt("max_score"), rs.getString("feedback"),
                        nullableBool(rs.getObject("correct")), rs.getString("submitted_code"),
                        rs.getString("evaluation_json"), rs.getString("selected_option_ids_json")))
                .list();
    }

    /** 查询已完成 LearnUnit 评估的逐题反馈，供 Tutor 识别弱点。 */
    public List<QuestionAttempt> listQuestionAttemptsForLearnUnit(String journeyId, String learnUnitCode) {
        return jdbc.sql("""
                        SELECT qa.question_id, qa.assessment_attempt_id, qa.answer_json, qa.score, qa.max_score,
                          qa.feedback, qa.correct, qa.submitted_code, qa.evaluation_json, qa.selected_option_ids_json
                        FROM question_attempt qa
                        JOIN assessment_attempt aa ON aa.id = qa.assessment_attempt_id
                        WHERE aa.journey_id = :journeyId AND aa.learn_unit_code = :learnUnitCode AND aa.completed_at IS NOT NULL
                        ORDER BY aa.completed_at DESC, qa.rowid
                        """)
                .param("journeyId", journeyId)
                .param("learnUnitCode", learnUnitCode)
                .query((rs, rowNum) -> new QuestionAttempt(
                        rs.getString("question_id"), rs.getString("assessment_attempt_id"), rs.getString("answer_json"),
                        nullableInt(rs.getObject("score")), rs.getInt("max_score"), rs.getString("feedback"),
                        nullableBool(rs.getObject("correct")), rs.getString("submitted_code"),
                        rs.getString("evaluation_json"), rs.getString("selected_option_ids_json")))
                .list();
    }

    /** 保存一次由 Java 决定的 Learning Workflow 状态迁移。 */
    public void insertWorkflowTransition(WorkflowTransition transition) {
        jdbc.sql("""
                        INSERT INTO workflow_transition
                          (id, journey_id, from_state, action, to_state, payload_json, created_at)
                        VALUES (:id, :journeyId, :fromState, :action, :toState, :payloadJson, :createdAt)
                        """)
                .param("id", transition.id())
                .param("journeyId", transition.journeyId())
                .param("fromState", transition.fromState())
                .param("action", transition.action())
                .param("toState", transition.toState())
                .param("payloadJson", transition.payloadJson())
                .param("createdAt", transition.createdAt().toString())
                .update();
    }

    /** 按发生顺序恢复 Journey 的 Workflow 状态迁移。 */
    public List<WorkflowTransition> listWorkflowTransitions(String journeyId) {
        return jdbc.sql("""
                        SELECT id, journey_id, from_state, action, to_state, payload_json, created_at
                        FROM workflow_transition WHERE journey_id = :journeyId ORDER BY created_at, rowid
                        """)
                .param("journeyId", journeyId)
                .query((rs, rowNum) -> new WorkflowTransition(
                        rs.getString("id"), rs.getString("journey_id"), rs.getString("from_state"),
                        rs.getString("action"), rs.getString("to_state"), rs.getString("payload_json"),
                        Instant.parse(rs.getString("created_at"))))
                .list();
    }

    /** 建立或复用 Journey + LearnUnit 到 Tutor Session 的唯一关联。 */
    public void linkTutorSession(String journeyId, String learnUnitCode, String sessionId) {
        jdbc.sql("""
                        INSERT INTO tutor_session (id, journey_id, learn_unit_code, session_id)
                        VALUES (:id, :journeyId, :learnUnitCode, :sessionId)
                        ON CONFLICT(journey_id, learn_unit_code) DO UPDATE SET session_id = excluded.session_id
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("journeyId", journeyId)
                .param("learnUnitCode", learnUnitCode)
                .param("sessionId", sessionId)
                .update();
    }

    /** 查询 Journey + LearnUnit 已关联的 Tutor Session。 */
    public Optional<String> findTutorSessionId(String journeyId, String learnUnitCode) {
        return jdbc.sql("SELECT session_id FROM tutor_session WHERE journey_id = :journeyId AND learn_unit_code = :learnUnitCode")
                .param("journeyId", journeyId)
                .param("learnUnitCode", learnUnitCode)
                .query(String.class)
                .optional();
    }

    /** 通过 Tutor Session 反查 Learning 上下文。 */
    public Optional<TutorSessionLink> findTutorSessionBySessionId(String sessionId) {
        return jdbc.sql("SELECT journey_id, learn_unit_code, session_id FROM tutor_session WHERE session_id = :sessionId")
                .param("sessionId", sessionId)
                .query((rs, rowNum) -> new TutorSessionLink(
                        rs.getString("journey_id"), rs.getString("learn_unit_code"), rs.getString("session_id")))
                .optional();
    }

    /**
     * Tutor Session 与 Learning Journey LearnUnit 的关联。
     *
     * @param journeyId Journey 编码
     * @param learnUnitCode 当前 LearnUnit 编码
     * @param sessionId Tutor Session 主键
     */
    public record TutorSessionLink(String journeyId, String learnUnitCode, String sessionId) {
    }

    private LearnUnit mapLearnUnit(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LearnUnit(
                rs.getString("id"), rs.getString("language_code"), rs.getString("code"), rs.getString("name"),
                rs.getString("description"), rs.getInt("sequence"), list(rs.getString("prerequisite_learn_unit_codes")),
                rs.getInt("pass_score"), nullableInt(rs.getObject("min_coding_score")), rs.getInt("enabled") != 0,
                list(rs.getString("learning_objectives_json")), rs.getString("lesson_intro"),
                list(rs.getString("key_concepts_json")), list(rs.getString("examples_json")),
                rs.getInt("diagnostic_eligible") != 0);
    }

    private Question mapQuestion(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Question(
                rs.getString("id"), rs.getString("learn_unit_code"), QuestionType.valueOf(rs.getString("type")),
                rs.getInt("difficulty"), rs.getString("prompt"), rs.getInt("points"), rs.getString("config_json"),
                rs.getString("rubric_json"), rs.getString("language"), rs.getString("starter_code"),
                rs.getString("reference_concepts_json"), rs.getInt("diagnostic_eligible") != 0);
    }

    private LearningJourney mapJourney(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LearningJourney(
                rs.getString("id"), rs.getString("user_id"), rs.getString("language_code"), rs.getString("goal"),
                JourneyStatus.valueOf(rs.getString("status")), Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")), rs.getString("current_learn_unit_code"));
    }

    private LearnerLearnUnit mapLearnerLearnUnit(java.sql.ResultSet rs) throws java.sql.SQLException {
        String reason = rs.getString("pass_reason");
        return new LearnerLearnUnit(
                rs.getString("journey_id"), rs.getString("learn_unit_code"), LearnerLearnUnitStatus.valueOf(rs.getString("status")),
                rs.getInt("mastery_score"), rs.getInt("best_assessment_score"), rs.getInt("attempt_count"),
                reason == null ? null : PassReason.valueOf(reason), instant(rs.getString("started_at")),
                instant(rs.getString("passed_at")), instant(rs.getString("skipped_at")));
    }

    private Assessment mapAssessment(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Assessment(
                rs.getString("id"), rs.getString("journey_id"), rs.getString("learn_unit_code"),
                AssessmentType.valueOf(rs.getString("type")), AssessmentStatus.valueOf(rs.getString("status")),
                Instant.parse(rs.getString("created_at")), instant(rs.getString("completed_at")));
    }

    private AssessmentAttempt mapAttempt(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AssessmentAttempt(
                rs.getString("id"), rs.getString("assessment_id"), rs.getString("journey_id"),
                rs.getString("learn_unit_code"), rs.getInt("attempt_number"), nullableInt(rs.getObject("choice_score")),
                nullableInt(rs.getObject("coding_score")), nullableInt(rs.getObject("total_score")),
                nullableBool(rs.getObject("passed")), Instant.parse(rs.getString("started_at")),
                instant(rs.getString("completed_at")));
    }

    private String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize learning data", error);
        }
    }

    private List<String> list(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return MAPPER.readValue(value, MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception error) {
            throw new IllegalStateException("Invalid curriculum JSON", error);
        }
    }

    private static Integer nullableInt(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static Boolean nullableBool(Object value) {
        return value == null ? null : ((Number) value).intValue() != 0;
    }

    private static String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    private static Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }
}
