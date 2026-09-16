package com.db117.learnagent.language;

import java.util.List;

/** 提供不可变的 Workspace 初始化文件模板。 */
@FunctionalInterface
public interface WorkspaceTemplateProvider {
    /** 返回要复制到 Workspace 的文件模板。 */
    List<WorkspaceTemplate> templates();
}
