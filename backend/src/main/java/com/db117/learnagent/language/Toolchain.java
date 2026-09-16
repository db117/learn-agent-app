package com.db117.learnagent.language;

import com.db117.learnagent.shared.domain.DomainChecks;

/**
 * LanguagePack 所声明的工具链名称；这里只保存元数据，不执行任何工具。
 *
 * @param runtime 运行时名称，例如 {@code node}
 * @param packageManager 包管理器名称，例如 {@code pnpm}
 * @param compiler 编译器名称，例如 {@code tsc}
 * @param testRunner 测试运行器名称，例如 {@code vitest}
 */
public record Toolchain(String runtime, String packageManager, String compiler, String testRunner) {
    public Toolchain {
        runtime = DomainChecks.text(runtime, "runtime");
        packageManager = DomainChecks.text(packageManager, "packageManager");
        compiler = DomainChecks.text(compiler, "compiler");
        testRunner = DomainChecks.text(testRunner, "testRunner");
    }
}
