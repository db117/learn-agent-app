package com.example.agent.learning.journey;

import com.example.agent.learning.catalog.CurriculumGenerator;

import java.util.List;

/** 面向公开生成事件的大纲安全投影；不包含评分规则或内部编码。 */
public record JourneyOutlinePreview(
        int chapterCount,
        int learnUnitCount,
        List<ChapterPreview> chapters) {

    public JourneyOutlinePreview {
        chapters = List.copyOf(chapters == null ? List.of() : chapters);
    }

    public static JourneyOutlinePreview from(CurriculumGenerator.GeneratedOutline outline) {
        List<ChapterPreview> chapters = outline.chapters().stream()
                .map(chapter -> new ChapterPreview(
                        chapter.sequence(), chapter.name(), chapter.goal(),
                        (int) outline.learnUnits().stream()
                                .filter(unit -> unit.chapterCode().equals(chapter.code())).count(),
                        outline.learnUnits().stream()
                                .filter(unit -> unit.chapterCode().equals(chapter.code()))
                                .map(unit -> new LearnUnitPreview(unit.sequence(), unit.name(), unit.description()))
                                .toList()))
                .toList();
        return new JourneyOutlinePreview(outline.chapters().size(), outline.learnUnits().size(), chapters);
    }

    public record ChapterPreview(
            int sequence,
            String name,
            String goal,
            int learnUnitCount,
            List<LearnUnitPreview> learnUnits) {

        public ChapterPreview {
            learnUnits = List.copyOf(learnUnits == null ? List.of() : learnUnits);
        }
    }

    public record LearnUnitPreview(int sequence, String name, String description) {
    }
}
