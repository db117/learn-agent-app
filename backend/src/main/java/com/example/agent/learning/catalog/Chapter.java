package com.example.agent.learning.catalog;

import java.util.List;

/** Journey 专属课程中的一个 Chapter。 */
public record Chapter(
        String id,
        String code,
        String name,
        String goal,
        int sequence,
        List<String> prerequisiteChapterCodes) {

    public Chapter {
        prerequisiteChapterCodes = List.copyOf(
                prerequisiteChapterCodes == null ? List.of() : prerequisiteChapterCodes);
    }
}
