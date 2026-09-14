package com.example.agent.learning.assessment;

import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.persistence.LearningRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 三个 Assessment 工作流共用的固定 Question 集规则。 */
final class AssessmentQuestionSet {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LearningRepository repository;

    AssessmentQuestionSet(LearningRepository repository) {
        this.repository = repository;
    }

    List<Question> normalize(
            List<Question> proposed, List<LearnUnit> learnUnits, List<Question> available, int minimumEvidence) {
        if (proposed == null || proposed.isEmpty()) throw new IllegalStateException("Generated question set is empty");
        Map<String, Question> byId = new HashMap<>();
        available.forEach(question -> byId.put(question.id(), question));
        List<Question> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Question question : proposed) {
            if (question == null) throw new IllegalArgumentException("Question is required");
            Question existing = byId.get(question.id());
            if (existing != null && !existing.equals(question))
                throw new IllegalArgumentException("Existing question was changed");
            LearnUnit learnUnit = learnUnits.stream()
                    .filter(candidate -> candidate.code().equals(question.learnUnitCode()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Question belongs to an unknown learnUnit"));
            if (question.type() == QuestionType.CODING && learnUnit.minCodingScore() == null) {
                throw new IllegalArgumentException("Coding question has no coding learning objective: " + learnUnit.code());
            }
            if (minimumEvidence > 1 && question.role() != QuestionRole.DIAGNOSTIC) {
                throw new IllegalArgumentException("Diagnostic question must be eligible: " + question.id());
            }
            QuestionStructureValidator.validate(question, learnUnit);
            if (!ids.add(question.id())) throw new IllegalArgumentException("Duplicate question: " + question.id());
            result.add(question);
        }
        if (minimumEvidence > 1) {
            for (LearnUnit learnUnit : learnUnits) {
                for (QuestionType type : learnUnit.minCodingScore() == null
                        ? List.of(QuestionType.MULTIPLE_CHOICE)
                        : List.of(QuestionType.MULTIPLE_CHOICE, QuestionType.CODING)) {
                    boolean covered = result.stream().anyMatch(question -> question.learnUnitCode().equals(learnUnit.code()) && question.type() == type);
                    if (!covered) {
                        available.stream()
                                .filter(question -> question.learnUnitCode().equals(learnUnit.code()) && question.type() == type)
                                .filter(question -> ids.add(question.id()))
                                .findFirst()
                                .ifPresent(result::add);
                    }
                }
                while (result.stream().filter(question -> question.learnUnitCode().equals(learnUnit.code())).count()
                        < minimumEvidence) {
                    int before = result.size();
                    available.stream()
                            .filter(question -> question.learnUnitCode().equals(learnUnit.code()))
                            .filter(question -> ids.add(question.id()))
                            .findFirst()
                            .ifPresent(result::add);
                    if (result.size() == before) break;
                }
            }
        }
        for (Question question : result) {
            LearnUnit learnUnit = learnUnits.stream()
                    .filter(candidate -> candidate.code().equals(question.learnUnitCode()))
                    .findFirst().orElseThrow();
            if (minimumEvidence > 1 && question.role() != QuestionRole.DIAGNOSTIC) {
                throw new IllegalArgumentException("Diagnostic question must be eligible: " + question.id());
            }
            QuestionStructureValidator.validate(question, learnUnit);
        }
        for (LearnUnit learnUnit : learnUnits) {
            boolean hasChoice = result.stream().anyMatch(question ->
                    question.learnUnitCode().equals(learnUnit.code()) && question.type() == QuestionType.MULTIPLE_CHOICE);
            boolean hasCoding = result.stream().anyMatch(question ->
                    question.learnUnitCode().equals(learnUnit.code()) && question.type() == QuestionType.CODING);
            long evidence = result.stream()
                    .filter(question -> question.learnUnitCode().equals(learnUnit.code()))
                    .count();
            if (evidence < minimumEvidence
                    || minimumEvidence > 1 && (!hasChoice || learnUnit.minCodingScore() != null && !hasCoding)) {
                throw new IllegalStateException("Assessment coverage is incomplete for " + learnUnit.code());
            }
        }
        return result;
    }

    boolean hasDiagnosticCoverage(List<Question> questions, List<LearnUnit> learnUnits, int minimumEvidence) {
        return !learnUnits.isEmpty() && learnUnits.stream().allMatch(learnUnit -> {
            long evidence = questions.stream()
                    .filter(question -> question.learnUnitCode().equals(learnUnit.code()))
                    .filter(question -> question.role() == QuestionRole.DIAGNOSTIC)
                    .count();
            boolean hasChoice = questions.stream().anyMatch(question ->
                    question.learnUnitCode().equals(learnUnit.code())
                            && question.role() == QuestionRole.DIAGNOSTIC && question.type() == QuestionType.MULTIPLE_CHOICE);
            boolean hasCoding = questions.stream().anyMatch(question ->
                    question.learnUnitCode().equals(learnUnit.code())
                            && question.role() == QuestionRole.DIAGNOSTIC && question.type() == QuestionType.CODING);
            return evidence >= minimumEvidence && hasChoice
                    && (learnUnit.minCodingScore() == null || hasCoding);
        });
    }

    void insertNewQuestions(List<Question> selected, List<Question> available) {
        for (Question question : selected) {
            if (available.stream().noneMatch(existing -> existing.id().equals(question.id()))) {
                repository.insertGeneratedQuestion(question);
            }
        }
    }

    List<Question> validateSynthesisQuestions(List<Question> questions, String chapterCode) {
        if (questions == null || questions.isEmpty()) {
            throw new IllegalStateException("Chapter synthesis question set is empty: " + chapterCode);
        }
        Set<String> ids = new HashSet<>();
        for (Question question : questions) {
            if (question == null || question.role() != QuestionRole.SYNTHESIS
                    || !chapterCode.equals(question.chapterCode()) || question.learnUnitCode() != null
                    || !ids.add(question.id())) {
                throw new IllegalArgumentException("Invalid Chapter synthesis question ownership: "
                        + (question == null ? "null" : question.id()));
            }
            QuestionStructureValidator.validate(question);
        }
        return questions;
    }

    List<String> coveredLearnUnitCodes(List<Question> questions, List<LearnUnit> learnUnits) {
        Set<String> codes = learnUnits.stream().map(LearnUnit::code).collect(java.util.stream.Collectors.toSet());
        Set<String> covered = new LinkedHashSet<>();
        for (Question question : questions) {
            try {
                JsonNode concepts = MAPPER.readTree(question.referenceConceptsJson());
                if (concepts != null && concepts.isArray()) {
                    for (JsonNode concept : concepts) {
                        if (concept.isTextual() && codes.contains(concept.textValue()))
                            covered.add(concept.textValue());
                    }
                }
            } catch (Exception ignored) {
                return List.of();
            }
        }
        return List.copyOf(covered);
    }
}
