package com.example.agent.config;

/** 表示另一个导入或导出操作已经占用唯一的传输槽位。 */
public final class DatabaseTransferBusyException extends IllegalStateException {
    public DatabaseTransferBusyException() {
        super("Another database export or import is already in progress; try again later.");
    }
}
