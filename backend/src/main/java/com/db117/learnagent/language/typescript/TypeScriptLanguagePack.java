package com.db117.learnagent.language.typescript;

import com.db117.learnagent.language.LanguageMetadata;
import com.db117.learnagent.language.LanguagePack;
import com.db117.learnagent.language.Toolchain;
import com.db117.learnagent.language.WorkspaceTemplate;
import com.db117.learnagent.language.WorkspaceTemplateProvider;
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
            new WorkspaceTemplate("src/index.ts", "export {};")
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
