package com.db117.learnagent.language;

import com.db117.learnagent.language.typescript.TypeScriptLanguagePack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LanguagePackTest {
    @Test
    void builtInTypeScriptPackDeclaresStableCore() {
        var pack = new TypeScriptLanguagePack();

        assertEquals("typescript", pack.id());
        assertEquals("TypeScript", pack.metadata().displayName());
        assertEquals(Set.of("ts", "tsx"), pack.metadata().fileExtensions());
        assertEquals("node", pack.toolchain().runtime());
        assertEquals("pnpm", pack.toolchain().packageManager());
        assertEquals("tsc", pack.toolchain().compiler());
        assertEquals("vitest", pack.toolchain().testRunner());
        var templates = pack.templates().templates();
        assertEquals(List.of("package.json", "tsconfig.json", "vitest.config.mjs", "src/index.ts",
                        "src/index.test.mjs"),
                templates.stream().map(WorkspaceTemplate::path).toList());
        assertTrue(templates.get(0).content().contains("\"type\": \"module\""));
        assertTrue(templates.get(1).content().contains("\"noEmit\": true"));
        assertEquals("export {};", templates.get(3).content());
        assertTrue(templates.get(4).content().contains("starter workspace is ready"));
    }

    @Test
    void catalogFindsByIdAndRejectsUnknownId() {
        var pack = new TypeScriptLanguagePack();
        var catalog = new LanguagePackCatalog(List.of(pack));

        assertEquals(pack, catalog.get("typescript"));
        var error = assertThrows(IllegalArgumentException.class, () -> catalog.get("rust"));
        assertEquals("Unknown language pack id: rust", error.getMessage());
    }

    @Test
    void catalogRejectsDuplicateIds() {
        var error = assertThrows(IllegalStateException.class,
                () -> new LanguagePackCatalog(List.of(
                        new TypeScriptLanguagePack(), new TypeScriptLanguagePack())));

        assertEquals("Duplicate language pack id: typescript", error.getMessage());
    }

    @Test
    void workspaceTemplateMustStayInsideWorkspace() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> new WorkspaceTemplate("../outside.ts", "export {};"));

        assertEquals("path must be a relative Workspace path: ../outside.ts", error.getMessage());
    }
}
