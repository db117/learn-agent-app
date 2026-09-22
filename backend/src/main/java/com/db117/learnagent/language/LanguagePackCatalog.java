package com.db117.learnagent.language;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;

/** 收集内置 CDI LanguagePack，并提供按稳定 id 的查找。 */
@ApplicationScoped
public class LanguagePackCatalog {
    private final Map<String, LanguagePack> packsById;

    @Inject
    public LanguagePackCatalog(Instance<LanguagePack> languagePacks) {
        this((Iterable<? extends LanguagePack>) languagePacks);
    }

    // 仅供本 package 单元测试注入确定性 pack 集合；运行时入口仍是 CDI Instance。
    LanguagePackCatalog(Iterable<? extends LanguagePack> languagePacks) {
        if (languagePacks == null) {
            throw new IllegalArgumentException("languagePacks must not be null");
        }
        LinkedHashMap<String, LanguagePack> packs = new LinkedHashMap<String, LanguagePack>();
        for (LanguagePack languagePack : languagePacks) {
            if (languagePack == null) {
                throw new IllegalArgumentException("languagePack must not be null");
            }
            String id = languagePack.id();
            if (id == null || id.isBlank()) {
                throw new IllegalStateException("Language pack id must not be blank");
            }
            if (packs.putIfAbsent(id, languagePack) != null) {
                throw new IllegalStateException("Duplicate language pack id: " + id);
            }
        }
        packsById = Map.copyOf(packs);
    }

    /** 按 id 返回 LanguagePack；未知 id 以明确异常失败。 */
    public LanguagePack get(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("languagePackId must not be blank");
        }
        LanguagePack languagePack = packsById.get(id);
        if (languagePack == null) {
            throw new IllegalArgumentException("Unknown language pack id: " + id);
        }
        return languagePack;
    }
}
