import {describe, expect, it} from "vitest";
import {parseLearningOutline} from "./learningOutline";

describe("parseLearningOutline", () => {
    it("parses the chapter and unit titles and objectives", () => {
        expect(parseLearningOutline(JSON.stringify({
            chapters: [{
                code: "typescript-basics",
                title: " TypeScript 基础 ",
                units: [{code: "variables", title: " 变量 ", objective: " 能声明变量 "}],
            }],
        }))).toEqual({
            chapters: [{title: "TypeScript 基础", units: [{title: "变量", objective: "能声明变量"}]}],
            unitCount: 1,
        });
    });

    it("rejects incomplete outlines and more than 50 units", () => {
        const tooManyUnits = Array.from({length: 51}, (_, index) => ({
            code: `unit-${index}`,
            title: "单元",
            objective: "学习目标",
        }));

        expect(parseLearningOutline('{"chapters":[]}')).toBeNull();
        expect(parseLearningOutline(JSON.stringify({
            chapters: [
                {code: "basics", title: "基础", units: tooManyUnits},
            ]
        }))).toBeNull();
    });
});
