package com.db117.learnagent.agent.tool;

import com.db117.learnagent.agent.api.TutorSessionMode;
import com.db117.learnagent.agent.application.TutorContext;
import com.db117.learnagent.learning.application.JourneyApplicationService;
import com.db117.learnagent.learning.application.LearningRequestException;
import com.db117.learnagent.learning.domain.LearnUnit;
import com.db117.learnagent.learning.domain.LearningJourney;
import com.db117.learnagent.practice.domain.ChoiceOption;
import com.db117.learnagent.practice.domain.ChoiceQuestion;
import com.db117.learnagent.practice.domain.PracticeAttempt;
import com.db117.learnagent.practice.domain.PracticeEvidence;
import com.db117.learnagent.practice.domain.PracticeTask;
import com.db117.learnagent.practice.domain.PracticeTaskRepository;
import com.db117.learnagent.practice.domain.PracticeTaskStatus;
import com.db117.learnagent.practice.domain.RuntimeResult;
import com.db117.learnagent.practice.domain.VerificationPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** TutorAgent 的选择题领域工具；只校验并持久化题目和答案，不调用模型。 */
@Dependent
public final class TutorPracticeTools {
    private static final String CHOICE_TASK_TYPE = "CHOICE";
    private static final List<String> OPTION_IDS = List.of("a", "b", "c", "d");
    private static final int MAX_QUESTION_LENGTH = 20_000;

    private final JourneyApplicationService journeys;
    private final PracticeTaskRepository practiceTasks;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    public TutorPracticeTools(
            JourneyApplicationService journeys,
            PracticeTaskRepository practiceTasks) {
        this.journeys = Objects.requireNonNull(journeys, "journeys must not be null");
        this.practiceTasks = Objects.requireNonNull(practiceTasks, "practiceTasks must not be null");
    }

    @Tool(
            name = "save_practice_test",
            description = "校验当前 LearnUnit 的四选一题目并保存到 SQLite；返回题目和 taskId，但不返回正确答案。")
    public String savePracticeTest(
            TutorContext context,
            @ToolParam(name = "question_json", description = "符合 Skill JSON 契约的题目；包含 prompt、四个 options 和 correctOptionId")
            String questionJson) {
        var journey = currentJourney(context);
        var unit = currentUnit(journey);
        var question = parseQuestion(questionJson);
        var task = practiceTasks.findByLearnUnit(journey.id(), requireLearnUnitId(unit)).stream()
                .filter(value -> value.status() == PracticeTaskStatus.OPEN)
                .filter(value -> CHOICE_TASK_TYPE.equals(value.type()))
                .findFirst()
                .orElseGet(() -> practiceTasks.save(PracticeTask.create(
                        journey.id(),
                        requireLearnUnitId(unit),
                        journey.languagePackId(),
                        CHOICE_TASK_TYPE,
                        "选择题：" + unit.title(),
                        unit.practiceInstruction(),
                        1,
                        "",
                        question,
                        new VerificationPolicy(false, false, false, false, true),
                        Instant.now())));
        return safeQuestion(task);
    }

    @Tool(
            name = "verify_practice_test",
            description = "校验学习者提交的选择题选项，追加 PracticeEvidence，并在答对时推进 Learning Domain。")
    public String verifyPracticeTest(
            TutorContext context,
            @ToolParam(name = "task_id", description = "save_practice_test 返回的 PracticeTask ID") long taskId,
            @ToolParam(name = "option_id", description = "学习者选择的选项 ID") String optionId) {
        var journey = currentJourney(context);
        var unit = currentUnit(journey);
        if (optionId == null || optionId.isBlank()) {
            throw LearningRequestException.badRequest("INVALID_PRACTICE_ANSWER", "optionId 不能为空");
        }
        var task = practiceTasks.findById(taskId)
                .filter(value -> value.journeyId() == journey.id())
                .orElseThrow(() -> LearningRequestException.notFound(
                        "PRACTICE_TASK_NOT_FOUND", "PracticeTask 不存在"));
        if (!CHOICE_TASK_TYPE.equals(task.type())) {
            throw LearningRequestException.badRequest("NOT_CHOICE_TASK", "该 PracticeTask 不是选择题");
        }
        if (task.status() != PracticeTaskStatus.OPEN || task.learnUnitId() != requireLearnUnitId(unit)) {
            throw LearningRequestException.conflict("CHOICE_TASK_NOT_OPEN", "选择题不属于当前可答的 LearnUnit");
        }
        var question = Objects.requireNonNull(task.choiceQuestion(), "choice task question must not be null");
        if (!question.hasOption(optionId)) {
            throw LearningRequestException.badRequest("INVALID_PRACTICE_ANSWER", "optionId 不属于当前选择题");
        }

        var correct = question.isCorrect(optionId);
        var evidence = new PracticeEvidence(
                false,
                false,
                0,
                false,
                RuntimeResult.NOT_RUN,
                List.of(),
                correct ? Instant.now() : null,
                correct);
        var saved = practiceTasks.save(task.recordAttempt(
                PracticeAttempt.submit(evidence, Instant.now())));
        var updated = evidence.isVerified(task.verificationPolicy())
                ? journeys.recordPracticeVerified(context.journeyId(), unit.code())
                : journey;
        return verificationResult(saved, evidence, journey, updated);
    }

    private LearningJourney currentJourney(TutorContext context) {
        if (context == null || context.mode() != TutorSessionMode.LEARNING) {
            throw new IllegalStateException("Practice 工具只在 LEARNING Session 中可用");
        }
        var journey = journeys.learningJourneyFor(context.journeyId());
        var current = journey.currentItem();
        if (current == null) {
            throw LearningRequestException.conflict("LEARNING_JOURNEY_COMPLETED", "学习路径已经完成");
        }
        if (current.practiceVerified()) {
            throw LearningRequestException.conflict("PRACTICE_ALREADY_VERIFIED", "当前单元的 Practice 已验证");
        }
        return journey;
    }

    private static LearnUnit currentUnit(LearningJourney journey) {
        return journey.learnUnit(Objects.requireNonNull(journey.currentItem()).learnUnitCode());
    }

    private ChoiceQuestion parseQuestion(String questionJson) {
        if (questionJson == null || questionJson.isBlank() || questionJson.length() > MAX_QUESTION_LENGTH) {
            throw LearningRequestException.badRequest("INVALID_PRACTICE_TEST", "题目 JSON 不能为空或过长");
        }
        try {
            var root = objectMapper.readTree(questionJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("question must be a JSON object");
            }
            var optionsNode = root.get("options");
            if (!(optionsNode instanceof ArrayNode) || optionsNode.size() != OPTION_IDS.size()) {
                throw new IllegalArgumentException("question must contain four options");
            }
            var options = new java.util.ArrayList<ChoiceOption>();
            for (int index = 0; index < OPTION_IDS.size(); index++) {
                var option = optionsNode.get(index);
                var id = requiredText(option, "id");
                if (!OPTION_IDS.get(index).equals(id)) {
                    throw new IllegalArgumentException("option ids must be a, b, c, d in order");
                }
                options.add(new ChoiceOption(id, requiredText(option, "label")));
            }
            return new ChoiceQuestion(
                    requiredText(root, "prompt"),
                    options,
                    requiredText(root, "correctOptionId"));
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw LearningRequestException.badRequest(
                    "INVALID_PRACTICE_TEST", "题目必须包含有效的题干、四个选项和一个正确答案");
        }
    }

    private String safeQuestion(PracticeTask task) {
        var question = Objects.requireNonNull(task.choiceQuestion(), "choice task question must not be null");
        var result = new LinkedHashMap<String, Object>();
        result.put("taskId", Objects.requireNonNull(task.id(), "saved practice task id must not be null"));
        result.put("title", task.title());
        result.put("prompt", question.prompt());
        result.put("options", question.options());
        return json(result);
    }

    private String verificationResult(
            PracticeTask task,
            PracticeEvidence evidence,
            LearningJourney before,
            LearningJourney after) {
        var result = new LinkedHashMap<String, Object>();
        result.put("taskId", Objects.requireNonNull(task.id(), "saved practice task id must not be null"));
        result.put("status", task.status().name());
        result.put("verified", evidence.isVerified(task.verificationPolicy()));
        result.put("choiceCorrect", evidence.choiceCorrect());
        result.put("verifiedAt", evidence.verifiedAt() == null ? null : evidence.verifiedAt().toString());
        result.put("learningJourneyStatus", after.status().name());
        result.put("currentLearnUnitCode", after.currentItem() == null ? null : after.currentItem().learnUnitCode());
        result.put("advanced", !Objects.equals(
                before.currentItem() == null ? null : before.currentItem().learnUnitCode(),
                after.currentItem() == null ? null : after.currentItem().learnUnitCode()));
        return json(result);
    }

    private static String requiredText(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field) || !node.get(field).isTextual()
                || node.get(field).asText().isBlank()) {
            throw new IllegalArgumentException("question field must be text: " + field);
        }
        return node.get(field).asText().strip();
    }

    private static long requireLearnUnitId(LearnUnit unit) {
        return Objects.requireNonNull(unit.id(), "persisted LearnUnit id must not be null");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法序列化 Practice 工具结果", error);
        }
    }
}
