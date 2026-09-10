package com.example.agent.api;

import com.example.agent.agent.EventHub;
import com.example.agent.config.DatabaseAgentBusyException;
import com.example.agent.config.DatabaseExportService;
import com.example.agent.config.DatabaseImportException;
import com.example.agent.config.DatabaseTransferBusyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 提供可移植 SQLite 文件和框架无关传输响应的 HTTP 边界。 */
@RestController
@RequestMapping("/api/database")
public class DatabaseController {

    private final DatabaseExportService exports;
    private final EventHub eventHub;

    public DatabaseController(DatabaseExportService exports) {
        this(exports, null);
    }

    @Autowired
    public DatabaseController(DatabaseExportService exports, EventHub eventHub) {
        this.exports = exports;
        this.eventHub = eventHub;
    }

    /** 下载一致的 SQLite 快照，不向 HTTP 边界暴露 JDBC 或 AgentScope 类型。 */
    @GetMapping("/export")
    public Mono<ResponseEntity<byte[]>> export() {
        return Mono.fromCallable(exports::export)
                .subscribeOn(Schedulers.boundedElastic())
                .map(snapshot -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .contentLength(snapshot.bytes().length)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                ContentDisposition.attachment().filename(snapshot.filename()).build().toString())
                        .body(snapshot.bytes()));
    }

    /** 将候选快照以流式方式交给服务；过期文件必须显式传入 confirm=true。 */
    @PostMapping("/import")
    public Mono<ResponseEntity<ImportResponse>> importDatabase(
            @RequestParam(defaultValue = "false") boolean confirm,
            @RequestBody Flux<DataBuffer> body) {
        return exports.importDatabase(body, confirm).map(result -> {
            if (eventHub != null) eventHub.reset();
            return ResponseEntity.ok(new ImportResponse(
                    result.schemaVersion(), result.importedAt(), result.restartRequired()));
        });
    }

    /** 将未预期的导出失败映射为稳定的项目错误，避免泄漏内部实现。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> exportError(IllegalStateException error) {
        return ResponseEntity.internalServerError().body(new ErrorResponse("database_export_failed", error.getMessage()));
    }

    /** 将校验、过期、备份和替换失败映射为对应的项目错误码。 */
    @ExceptionHandler(DatabaseImportException.class)
    public ResponseEntity<ErrorResponse> importError(DatabaseImportException error) {
        HttpStatus status = switch (error.errorCode()) {
            case "database_import_invalid_file", "database_import_schema_unknown", "database_import_schema_version" ->
                    HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        if ("database_import_stale".equals(error.errorCode())) status = HttpStatus.CONFLICT;
        return status == HttpStatus.UNPROCESSABLE_ENTITY
                ? ResponseEntity.unprocessableContent().body(new ErrorResponse(error.errorCode(), error.getMessage()))
                : ResponseEntity.status(status).body(new ErrorResponse(
                error.errorCode(), error.getMessage(), error.snapshotCreatedAt(), error.currentDatabaseAt(),
                "database_import_stale".equals(error.errorCode())));
    }

    /** 维护窗口被另一个传输操作占用时，返回可重试的冲突响应。 */
    @ExceptionHandler(DatabaseTransferBusyException.class)
    public ResponseEntity<ErrorResponse> busy(DatabaseTransferBusyException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("database_transfer_busy", error.getMessage()));
    }

    /** 不取消活动 TutorAgent 调用，并返回可重试的冲突响应。 */
    @ExceptionHandler(DatabaseAgentBusyException.class)
    public ResponseEntity<ErrorResponse> agentBusy(DatabaseAgentBusyException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("database_agent_busy", error.getMessage()));
    }

    /** 供 React 消费的错误 DTO；过期响应还会携带两个比较时间。 */
    public record ErrorResponse(
            String error,
            String detail,
            java.time.Instant snapshotCreatedAt,
            java.time.Instant currentDatabaseAt,
            boolean confirmationRequired) {
        public ErrorResponse(String error, String detail) {
            this(error, detail, null, null, false);
        }
    }

    /** 成功 DTO，仅保留可移植的 HTTP 协议字段。 */
    public record ImportResponse(String schemaVersion, java.time.Instant importedAt, boolean restartRequired) {
    }
}
