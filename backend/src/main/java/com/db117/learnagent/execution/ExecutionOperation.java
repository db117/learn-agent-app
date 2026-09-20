package com.db117.learnagent.execution;

/** 可由未来执行环境实现的受限操作集合，不包含任意 shell。 */
public enum ExecutionOperation {
    INITIALIZE_NPM_PROJECT,
    INSTALL_TYPESCRIPT,
    COMPILE_PROJECT,
    COMPILE,
    RUN_TESTS,
    RUN_PROGRAM,
    FORMAT,
    LINT
}
