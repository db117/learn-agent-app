package com.example.agent.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;
import org.sqlite.SQLiteConnection;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import javax.sql.DataSource;

/**
 * 通过可移植快照传输当前唯一的 SQLite 数据库。
 *
 * <p>协调器 lease 覆盖整个操作；控制器把阻塞的 JDBC 和文件操作调度到 {@code boundedElastic}。
 * 活动数据库不会通过复制实时文件或 WAL/SHM 旁车文件来读取。</p>
 */
@Service
public class DatabaseExportService {

    private static final Duration STALE_SNAPSHOT_THRESHOLD = Duration.ofMinutes(1);
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(java.time.ZoneOffset.UTC);

    private final DataSource dataSource;
    private final DatabaseTransferCoordinator transferCoordinator;

    @Autowired
    public DatabaseExportService(DataSource dataSource, DatabaseTransferCoordinator transferCoordinator) {
        this.dataSource = dataSource;
        this.transferCoordinator = transferCoordinator;
    }

    public DatabaseExportService(DataSource dataSource) {
        this.dataSource = dataSource;
        this.transferCoordinator = new DatabaseTransferCoordinator();
    }

    /** 创建独立的在线备份快照，并将快照物化为响应字节。 */
    public ExportedDatabase export() {
        DatabaseTransferCoordinator.Lease lease = transferCoordinator.beginExport();
        Path snapshot = null;
        try {
            snapshot = Files.createTempFile("learning-agent-java-export-", ".db");
            Files.deleteIfExists(snapshot);
            Instant createdAt = Instant.now();
            backupTo(snapshot, createdAt);
            byte[] bytes = Files.readAllBytes(snapshot);
            return new ExportedDatabase(bytes, "learning-agent-java-" + FILE_TIME.format(createdAt) + ".db");
        } catch (SQLException | IOException error) {
            throw new IllegalStateException("Unable to export SQLite database", error);
        } finally {
            if (snapshot != null) {
                try {
                    Files.deleteIfExists(snapshot);
                } catch (IOException ignored) {
                }
            }
            lease.close();
        }
    }

    /**
     * 先将上传内容流式写入临时文件，再执行校验和原子替换。
     *
     * <p>无论成功、取消、校验失败还是替换失败，都会清理临时文件和协调器 lease。</p>
     */
    public Mono<ImportResult> importDatabase(Flux<DataBuffer> body, boolean confirmStale) {
        return Mono.using(
                        transferCoordinator::beginImport,
                        lease -> createImportFile()
                                .flatMap(path -> writeUpload(body, path)
                                        .then(Mono.fromCallable(() -> importFile(path, confirmStale))
                                                .subscribeOn(Schedulers.boundedElastic()))
                                        .doFinally(signal -> cleanupImportFile(path))),
                        DatabaseTransferCoordinator.Lease::close)
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 导入数据库，但不绕过过期快照确认保护。 */
    public Mono<ImportResult> importDatabase(Flux<DataBuffer> body) {
        return importDatabase(body, false);
    }

    /** 在活动数据库旁创建上传文件，使替换始终发生在同一文件系统。 */
    private Mono<Path> createImportFile() {
        return Mono.fromCallable(() -> {
            Path directory = databasePath().getParent();
            Files.createDirectories(directory);
            return Files.createTempFile(directory, "learning-agent-java-import-", ".db");
        }).onErrorMap(error -> error instanceof DatabaseImportException importError
                ? importError
                : new DatabaseImportException("database_import_upload_failed", "无法创建导入临时文件。", error));
    }

    /** 一次写入一个请求缓冲区，不把完整上传内容累积到内存。 */
    private Mono<Void> writeUpload(Flux<DataBuffer> body, Path path) {
        return Mono.using(
                () -> FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
                channel -> body.publishOn(Schedulers.boundedElastic())
                        .doOnNext(buffer -> writeBuffer(channel, buffer))
                        .then(),
                this::closeChannel);
    }

    private void writeBuffer(FileChannel channel, DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            ByteBuffer source = ByteBuffer.wrap(bytes);
            while (source.hasRemaining()) channel.write(source);
        } catch (IOException error) {
            throw new DatabaseImportException("database_import_upload_failed", "导入文件写入失败。", error);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private void closeChannel(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException error) {
            throw new DatabaseImportException("database_import_upload_failed", "导入临时文件关闭失败。", error);
        }
    }

    /**
     * 先校验，再备份并替换；在此之前不修改活动数据库。
     *
     * <p>在移动文件前标记替换阶段，是为了覆盖文件移动成功后才出现注入故障或后续故障的情况。</p>
     */
    private ImportResult importFile(Path uploaded, boolean confirmStale) {
        DatabaseSchema.SnapshotMetadata imported = DatabaseSchema.validateSnapshot(uploaded);
        Path active = databasePath();
        Instant currentDatabaseAt = currentDatabaseTimestamp(active);
        if (!confirmStale && isStale(imported.createdAt(), currentDatabaseAt)) {
            throw new DatabaseImportException(
                    "database_import_stale",
                    "导入快照早于当前数据库，请确认后再覆盖当前数据。",
                    null,
                    imported.createdAt(),
                    currentDatabaseAt);
        }
        Path backup;
        Instant importedAt = Instant.now();
        try {
            backup = createPreImportBackup(active, importedAt);
        } catch (DatabaseImportException error) {
            throw error;
        }
        boolean replaced = false;
        try {
            replaced = true;
            replace(uploaded, active);
            try {
                DatabaseSchema.validateSnapshot(active);
            } catch (RuntimeException error) {
                throw new DatabaseImportException("database_import_replace_failed", "导入数据库替换后校验失败。", error);
            }
            return new ImportResult(imported.schemaVersion(), importedAt, true, backup.getFileName().toString());
        } catch (Exception error) {
            if (replaced) {
                try {
                    restoreBackup(backup, active);
                } catch (Exception restoreError) {
                    error.addSuppressed(restoreError);
                    throw new DatabaseImportException(
                            "database_import_replace_failed",
                            "数据库替换失败且无法恢复原数据库，请使用预导入备份恢复。",
                            error);
                }
            }
            throw error instanceof DatabaseImportException importError
                    && "database_import_replace_failed".equals(importError.errorCode())
                    ? importError
                    : new DatabaseImportException("database_import_replace_failed", "数据库整库替换失败，原数据库已保留。", error);
        }
    }

    /** 提供包可见的替换切口，用于确定性测试替换失败场景。 */
    void replace(Path uploaded, Path active) throws IOException {
        Files.move(uploaded, active, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path createPreImportBackup(Path active, Instant createdAt) {
        Path backup = null;
        try {
            Path backupDirectory = active.resolveSibling("backups");
            Files.createDirectories(backupDirectory);
            backup = Files.createTempFile(
                    backupDirectory, "learning-agent-java-pre-import-" + FILE_TIME.format(createdAt) + "-", ".db");
            Files.deleteIfExists(backup);
            backupTo(backup, createdAt);
            return backup;
        } catch (IOException | SQLException | RuntimeException error) {
            if (backup != null) {
                try {
                    Files.deleteIfExists(backup);
                } catch (IOException ignored) {
                }
            }
            throw new DatabaseImportException("database_import_backup_failed", "无法创建导入前数据库备份，原数据库未改变。", error);
        }
    }

    /** 使用活动文件时间戳作为过期导入的当前数据比较点。 */
    private Instant currentDatabaseTimestamp(Path active) {
        return DatabaseSchema.currentDataTime(active);
    }

    private boolean isStale(Instant snapshotCreatedAt, Instant currentDatabaseAt) {
        return snapshotCreatedAt.plus(STALE_SNAPSHOT_THRESHOLD).isBefore(currentDatabaseAt);
    }


    /** 使用 Xerial 在线备份 API，写入时间戳后重新校验独立目标文件。 */
    private void backupTo(Path destination, Instant createdAt) throws SQLException, IOException {
        try (Connection connection = dataSource.getConnection()) {
            SQLiteConnection sqlite = connection.unwrap(SQLiteConnection.class);
            sqlite.getDatabase().backup("main", destination.toString(), null);
        }
        try (Connection snapshotConnection = DriverManager.getConnection("jdbc:sqlite:" + destination)) {
            JdbcTemplate snapshotJdbc = new JdbcTemplate(new SingleConnectionDataSource(snapshotConnection, true));
            snapshotJdbc.update(
                    "UPDATE schema_metadata SET value = ? WHERE key = 'snapshot.created_at'", createdAt.toString());
        }
        DatabaseSchema.validateSnapshot(destination);
    }

    /** 通过同目录的另一个临时文件恢复，保证恢复替换仍然是原子的。 */
    private void restoreBackup(Path backup, Path active) throws IOException {
        Path restore = Files.createTempFile(active.getParent(), "learning-agent-java-restore-", ".db");
        try {
            Files.copy(backup, restore, StandardCopyOption.REPLACE_EXISTING);
            Files.move(restore, active, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(restore);
        }
    }

    /** 尽力清理可能已经移动到活动数据库路径的文件。 */
    private void cleanupImportFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    /** 解析实际配置的 SQLite 文件，不根据数据目录配置猜测路径。 */
    private Path databasePath() {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            String prefix = "jdbc:sqlite:";
            if (url == null || !url.startsWith(prefix))
                throw new IllegalStateException("SQLite database path is unavailable.");
            String value = url.substring(prefix.length());
            if (value.isBlank() || value.startsWith(":"))
                throw new IllegalStateException("SQLite database path is not a file.");
            return Path.of(value).toAbsolutePath().normalize();
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to resolve SQLite database path", error);
        }
    }

    /** HTTP 下载边界使用的框架无关载荷。 */
    public record ExportedDatabase(byte[] bytes, String filename) {
    }

    /** 快照替换活动数据库后返回的结果。 */
    public record ImportResult(String schemaVersion, Instant importedAt, boolean restartRequired,
                               String backupFilename) {
    }
}
