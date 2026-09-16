import {describe, expect, it, vi} from "vitest";
import {buildFileTree} from "./fileTree";
import {beginSave, editDraft, failSave, finishSave, initialSaveState, selectFile} from "./saveState";
import {createWorkspaceApi} from "./workspaceApi";

describe("workspace API", () => {
    it("encodes path segments and writes the JSON body", async () => {
        const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({
            path: "src/a b.ts",
            content: "x",
            size: 1,
            modifiedAt: "now"
        }), {status: 200}));
        await createWorkspaceApi(fetcher, "http://backend").writeFile("journey", 7, "src/a b.ts", "x");
        expect(fetcher).toHaveBeenCalledWith("http://backend/api/journeys/7/workspace/files/src/a%20b.ts", expect.objectContaining({
            method: "PUT",
            body: JSON.stringify({content: "x"})
        }));
    });

    it("hides non-success response details", async () => {
        const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response("secret", {status: 500}));
        await expect(createWorkspaceApi(fetcher).listFiles("project", 2)).rejects.toThrow("Workspace 请求失败");
        await expect(createWorkspaceApi(fetcher).listFiles("project", 2)).rejects.not.toThrow("secret");
    });
});

describe("file tree", () => {
    it("builds sorted directories and files from POSIX paths", () => {
        const tree = buildFileTree([{path: "z.ts", size: 1, modifiedAt: ""}, {
            path: ".env",
            size: 1,
            modifiedAt: ""
        }, {path: "src/main.ts", size: 1, modifiedAt: ""}]);
        expect(tree.children?.map((node) => node.path)).toEqual([".env", "src", "z.ts"]);
        expect(tree.children?.[1].children?.[0].path).toBe("src/main.ts");
    });
});

describe("save state", () => {
    it("tracks explicit dirty and save transitions", () => {
        let state = initialSaveState();
        state = selectFile("src/main.ts", "old");
        state = editDraft(state, "new");
        expect(state.dirty).toBe(true);
        state = beginSave(state);
        expect(state.saving).toBe(true);
        state = finishSave(state);
        expect(state.dirty).toBe(false);
        expect(state.loadedContent).toBe("new");
        expect(failSave(beginSave(state), "offline")).toMatchObject({saving: false, error: "offline", dirty: false});
    });
});
