package com.db117.learnagent.persistence.sqlite;

import com.db117.learnagent.practice.domain.ChoiceQuestion;
import com.db117.learnagent.practice.domain.VerificationPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

/** 仅用于把不可变值对象编码到 SQLite TEXT；不承担业务判定。 */
final class SqliteJson {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SqliteJson() {
    }

    /** 结构化值统一使用 JSON，避免为固定的小型快照增加额外表和映射代码。 */
    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to encode SQLite JSON", error);
        }
    }

    static List<String> strings(String value) {
        try {
            return MAPPER.readValue(value, new TypeReference<>() {
            });
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to decode SQLite JSON", error);
        }
    }

    static Set<String> stringSet(String value) {
        return Set.copyOf(strings(value));
    }

    static VerificationPolicy verificationPolicy(String value) {
        try {
            return MAPPER.readValue(value, VerificationPolicy.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to decode verification policy", error);
        }
    }

    static ChoiceQuestion choiceQuestion(String value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.readValue(value, ChoiceQuestion.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to decode choice question", error);
        }
    }
}
