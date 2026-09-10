package com.example.agent.config;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 协调 SQLite 维护窗口，不取消正在执行的 TutorAgent。
 *
 * <p>所有调用都会快速失败而不是排队等待：传输操作持有独占标记，Agent lease 则一直保留到终态事件
 * 完成持久化。</p>
 */
public class DatabaseTransferCoordinator {

    private int activeAgentRuns;
    private boolean transferInProgress;

    /** 为新的 Agent 调用获取 lease；传输开始后拒绝新的调用。 */
    public synchronized Lease beginAgentRun() {
        if (transferInProgress) throw new DatabaseTransferBusyException();
        activeAgentRuns++;
        return new Lease(() -> {
            synchronized (DatabaseTransferCoordinator.this) {
                activeAgentRuns--;
            }
        });
    }

    /** 为完整的导出快照操作获取独占传输 lease。 */
    public synchronized Lease beginExport() {
        if (transferInProgress) throw new DatabaseTransferBusyException();
        transferInProgress = true;
        return new Lease(() -> {
            synchronized (DatabaseTransferCoordinator.this) {
                transferInProgress = false;
            }
        });
    }

    /** 仅在没有活动 Agent 调用时获取独占传输 lease。 */
    public synchronized Lease beginImport() {
        if (transferInProgress) throw new DatabaseTransferBusyException();
        if (activeAgentRuns > 0) throw new DatabaseAgentBusyException();
        transferInProgress = true;
        return new Lease(() -> {
            synchronized (DatabaseTransferCoordinator.this) {
                transferInProgress = false;
            }
        });
    }

    /** 可幂等关闭的所有权令牌；关闭后只释放一次协调器状态。 */
    public static final class Lease implements AutoCloseable {

        private final Runnable release;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(Runnable release) {
            this.release = release;
        }

        /** 适用于取消或失败时可能重复执行的清理路径。 */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) release.run();
        }
    }
}
