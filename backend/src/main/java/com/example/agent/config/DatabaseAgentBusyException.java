package com.example.agent.config;

/** 表示导入必须等待正在执行的 TutorAgent 调用完成，不能取消该调用。 */
public final class DatabaseAgentBusyException extends IllegalStateException {
    public DatabaseAgentBusyException() {
        super("A TutorAgent call is still running; wait for it to finish and retry the database import.");
    }
}
