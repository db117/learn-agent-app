package com.db117.learnagent.language.typescript;

import com.db117.learnagent.language.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Set;

/** 内置 TypeScript LanguagePack；只声明元数据和初始 Workspace 模板。 */
@ApplicationScoped
public class TypeScriptLanguagePack implements LanguagePack {
    private static final LanguageMetadata METADATA = new LanguageMetadata(
            "TypeScript", Set.of("ts", "tsx"));
    private static final Toolchain TOOLCHAIN = new Toolchain(
            "node", "pnpm", "tsc", "vitest");
    private static final WorkspaceTemplateProvider TEMPLATES = () -> List.of(
            new WorkspaceTemplate("package.json", """
                    {
                      "name": "learn-agent-practice",
                      "private": true,
                      "type": "module",
                      "devDependencies": {
                        "typescript": "5.9.3",
                        "vitest": "5.0.0"
                      }
                    }
                    """),
            new WorkspaceTemplate("tsconfig.json", """
                    {
                      "compilerOptions": {
                        "target": "ES2022",
                        "module": "NodeNext",
                        "moduleResolution": "NodeNext",
                        "strict": true,
                        "skipLibCheck": true,
                        "noEmit": true
                      },
                      "include": ["src/**/*.ts"]
                    }
                    """),
            new WorkspaceTemplate("vitest.config.mjs", """
                    export default {
                      test: { globals: true }
                    };
                    """),
            new WorkspaceTemplate("src/index.ts", "export {};"),
            new WorkspaceTemplate("src/index.test.mjs", """
                    import assert from "node:assert/strict";
                    
                    it("starter workspace is ready", () => {
                      assert.equal(true, true);
                    });
                    """)
    );

    @Override
    public String id() {
        return "typescript";
    }

    @Override
    public LanguageMetadata metadata() {
        return METADATA;
    }

    @Override
    public Toolchain toolchain() {
        return TOOLCHAIN;
    }

    @Override
    public WorkspaceTemplateProvider templates() {
        return TEMPLATES;
    }
}
