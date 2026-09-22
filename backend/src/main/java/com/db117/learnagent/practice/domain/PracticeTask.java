package com.db117.learnagent.practice.domain;

import com.db117.learnagent.shared.domain.DomainChecks;
import com.db117.learnagent.shared.domain.DomainRuleViolation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Practice 聚合根；失败尝试保留历史，首个通过证据使任务进入 VERIFIED。选择题快照随任务固定。 */
public final class PracticeTask {
    private final Long id;
    private final long journeyId;
    private final long learnUnitId;
    private final String languagePackId;
    private final String type;
    private final String title;
    private final String description;
    private final int difficulty;
    private final String starterTemplate;
    private final ChoiceQuestion choiceQuestion;
    private final VerificationPolicy verificationPolicy;
    private final PracticeTaskStatus status;
    private final Instant createdAt;
    private final List<PracticeAttempt> attempts;

    private PracticeTask(
            Long id,
            long journeyId,
            long learnUnitId,
            String languagePackId,
            String type,
            String title,
            String description,
            int difficulty,
            String starterTemplate,
            ChoiceQuestion choiceQuestion,
            VerificationPolicy verificationPolicy,
            PracticeTaskStatus status,
            Instant createdAt,
            List<PracticeAttempt> attempts) {
        if (id != null && id <= 0) {
            throw new DomainRuleViolation("id must be positive");
        }
        DomainChecks.id(journeyId, "journeyId");
        DomainChecks.id(learnUnitId, "learnUnitId");
        this.id = id;
        this.journeyId = journeyId;
        this.learnUnitId = learnUnitId;
        this.languagePackId = DomainChecks.text(languagePackId, "languagePackId");
        this.type = DomainChecks.text(type, "type");
        this.title = DomainChecks.text(title, "title");
        this.description = DomainChecks.text(description, "description");
        if (difficulty < 0) {
            throw new DomainRuleViolation("difficulty must not be negative");
        }
        this.difficulty = difficulty;
        this.starterTemplate = starterTemplate == null ? "" : starterTemplate;
        this.choiceQuestion = choiceQuestion;
        if ("CHOICE".equals(this.type) && choiceQuestion == null) {
            throw new DomainRuleViolation("choice task needs a choice question");
        }
        if (!"CHOICE".equals(this.type) && choiceQuestion != null) {
            throw new DomainRuleViolation("only choice task can have a choice question");
        }
        this.verificationPolicy = verificationPolicy == null
                ? throwRule("verificationPolicy must not be null")
                : verificationPolicy;
        this.status = status == null ? throwRule("status must not be null") : status;
        this.createdAt = DomainChecks.time(createdAt, "createdAt");
        this.attempts = List.copyOf(attempts == null ? List.of() : attempts);
        if (status == PracticeTaskStatus.VERIFIED
                && this.attempts.stream().noneMatch(attempt -> attempt.evidence().isVerified(verificationPolicy))) {
            throw new DomainRuleViolation("verified task needs a passing evidence");
        }
    }

    private static <T> T throwRule(String message) {
        throw new DomainRuleViolation(message);
    }

    public static PracticeTask create(
            long journeyId,
            long learnUnitId,
            String languagePackId,
            String type,
            String title,
            String description,
            int difficulty,
            String starterTemplate,
            VerificationPolicy verificationPolicy,
            Instant createdAt) {
        return new PracticeTask(
                null,
                journeyId,
                learnUnitId,
                languagePackId,
                type,
                title,
                description,
                difficulty,
                starterTemplate,
                null,
                verificationPolicy,
                PracticeTaskStatus.OPEN,
                createdAt,
                List.of());
    }

    public static PracticeTask create(
            long journeyId,
            long learnUnitId,
            String languagePackId,
            String type,
            String title,
            String description,
            int difficulty,
            String starterTemplate,
            ChoiceQuestion choiceQuestion,
            VerificationPolicy verificationPolicy,
            Instant createdAt) {
        return new PracticeTask(
                null,
                journeyId,
                learnUnitId,
                languagePackId,
                type,
                title,
                description,
                difficulty,
                starterTemplate,
                choiceQuestion,
                verificationPolicy,
                PracticeTaskStatus.OPEN,
                createdAt,
                List.of());
    }

    /** 仅由持久化适配器恢复已有聚合，恢复时仍执行构造校验。 */
    public static PracticeTask reconstitute(
            long id,
            long journeyId,
            long learnUnitId,
            String languagePackId,
            String type,
            String title,
            String description,
            int difficulty,
            String starterTemplate,
            VerificationPolicy verificationPolicy,
            PracticeTaskStatus status,
            Instant createdAt,
            List<PracticeAttempt> attempts) {
        return reconstitute(
                id,
                journeyId,
                learnUnitId,
                languagePackId,
                type,
                title,
                description,
                difficulty,
                starterTemplate,
                null,
                verificationPolicy,
                status,
                createdAt,
                attempts);
    }

    /** 仅由持久化适配器恢复包含选择题快照的已有聚合。 */
    public static PracticeTask reconstitute(
            long id,
            long journeyId,
            long learnUnitId,
            String languagePackId,
            String type,
            String title,
            String description,
            int difficulty,
            String starterTemplate,
            ChoiceQuestion choiceQuestion,
            VerificationPolicy verificationPolicy,
            PracticeTaskStatus status,
            Instant createdAt,
            List<PracticeAttempt> attempts) {
        return new PracticeTask(
                DomainChecks.id(id, "id"),
                journeyId,
                learnUnitId,
                languagePackId,
                type,
                title,
                description,
                difficulty,
                starterTemplate,
                choiceQuestion,
                verificationPolicy,
                status,
                createdAt,
                attempts);
    }

    public Long id() {
        return id;
    }

    public long journeyId() {
        return journeyId;
    }

    public long learnUnitId() {
        return learnUnitId;
    }

    public String languagePackId() {
        return languagePackId;
    }

    public String type() {
        return type;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public int difficulty() {
        return difficulty;
    }

    public String starterTemplate() {
        return starterTemplate;
    }

    public ChoiceQuestion choiceQuestion() {
        return choiceQuestion;
    }

    public VerificationPolicy verificationPolicy() {
        return verificationPolicy;
    }

    public PracticeTaskStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public List<PracticeAttempt> attempts() {
        return attempts;
    }

    public PracticeTask recordAttempt(PracticeAttempt attempt) {
        // VERIFIED 是本任务的完成状态，后续提交不能覆盖已经确认的证据。
        if (status == PracticeTaskStatus.VERIFIED) {
            throw new DomainRuleViolation("verified task cannot receive another attempt");
        }
        if (attempt == null) {
            throw new DomainRuleViolation("attempt must not be null");
        }
        ArrayList<PracticeAttempt> nextAttempts = new ArrayList<>(attempts);
        nextAttempts.add(attempt);
        PracticeTaskStatus nextStatus = attempt.evidence().isVerified(verificationPolicy)
                ? PracticeTaskStatus.VERIFIED
                : PracticeTaskStatus.OPEN;
        return new PracticeTask(
                id,
                journeyId,
                learnUnitId,
                languagePackId,
                type,
                title,
                description,
                difficulty,
                starterTemplate,
                choiceQuestion,
                verificationPolicy,
                nextStatus,
                createdAt,
                nextAttempts);
    }

    public PracticeTask withPersistedIds(long persistedId, List<PracticeAttempt> persistedAttempts) {
        return new PracticeTask(
                DomainChecks.id(persistedId, "id"),
                journeyId,
                learnUnitId,
                languagePackId,
                type,
                title,
                description,
                difficulty,
                starterTemplate,
                choiceQuestion,
                verificationPolicy,
                status,
                createdAt,
                persistedAttempts);
    }
}
