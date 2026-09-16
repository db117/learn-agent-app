package com.db117.learnagent.language;

/** 面向一种编程语言的产品级支持包，只声明当前稳定的核心能力。 */
public interface LanguagePack {
    /** 返回在系统内稳定且唯一的语言包标识。 */
    String id();

    /** 返回语言展示元数据。 */
    LanguageMetadata metadata();

    /** 返回该语言所需的工具链元数据。 */
    Toolchain toolchain();

    /** 返回初始化 Workspace 所需的模板。 */
    WorkspaceTemplateProvider templates();
}
