package com.db117.learnagent.language;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证内置 TypeScript LanguagePack 能由 CDI Catalog 自动发现。 */
@QuarkusTest
class LanguagePackCdiTest {
    @Inject
    LanguagePackCatalog catalog;

    @Test
    void discoversBuiltInTypeScriptPack() {
        assertEquals("typescript", catalog.get("typescript").id());
    }
}
