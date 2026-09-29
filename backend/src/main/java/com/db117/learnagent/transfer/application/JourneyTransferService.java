package com.db117.learnagent.transfer.application;

import com.db117.learnagent.workspace.application.WorkspaceIgnoreRules;
import com.db117.learnagent.workspace.application.WorkspaceManager;
import com.db117.learnagent.workspace.domain.Workspace;
import com.db117.learnagent.workspace.domain.WorkspaceFileEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.sql.DataSource;

/** 将 Learning Domain 和受管 Journey Workspace 打包为用户可控的本地文件。 */
@ApplicationScoped
public class JourneyTransferService {
    private static final int FORMAT_VERSION = 3;
    private static final long MAX_ARCHIVE_BYTES = 128L * 1024 * 1024;
    private static final long MAX_WORKSPACE_FILE_BYTES = 2L * 1024 * 1024;
    private static final int MAX_JOURNEYS = 1_000;
    private static final int MAX_ARCHIVE_ENTRIES = 50_001;
    private static final int MAX_ROWS_PER_TABLE = 50_000;
    private static final int MAX_TOTAL_ROWS = 250_000;
    private static final String MANIFEST_ENTRY = "manifest.json";
    private static final List<String> TABLES = List.of(
            "chapter", "learn_unit", "practice_task", "practice_attempt", "practice_evidence",
            "practice_assessment", "learning_path_item");
    private static final Map<String, List<String>> TABLE_COLUMNS = Map.ofEntries(
            Map.entry("chapter", List.of("id", "journey_id", "code", "title", "sequence")),
            Map.entry("learn_unit", List.of("id", "journey_id", "chapter_id", "code", "title", "objective",
                    "content", "sequence", "prerequisite_codes")),
            Map.entry("learning_path_item", List.of("id", "journey_id", "learn_unit_id", "learn_unit_code",
                    "sequence", "status", "practice_verified", "assessment_id", "pass_reason", "started_at",
                    "completed_at", "updated_at")),
            Map.entry("practice_task", List.of("id", "journey_id", "learn_unit_id", "language_pack_id",
                    "title", "description", "difficulty", "starter_template",
                    "verification_policy", "status", "created_at")),
            Map.entry("practice_attempt", List.of("id", "practice_task_id", "submitted_at")),
            Map.entry("practice_evidence", List.of("id", "attempt_id", "compile_passed", "tests_passed",
                    "test_count", "lint_passed", "runtime_result", "submitted_files", "verified_at",
                    "workspace_digest")),
            Map.entry("practice_assessment", List.of("id", "journey_id", "learn_unit_id", "practice_task_id",
                    "practice_attempt_id", "verdict", "rationale", "workspace_digest", "created_at")));
    private static final List<String> LEARNING_JOURNEY_COLUMNS = List.of(
            "id", "language_pack_id", "title", "status", "created_at", "completed_at");

    private final DataSource dataSource;
    private final WorkspaceManager workspaces;
    private final ObjectMapper objectMapper;

    public JourneyTransferService(
            DataSource dataSource,
            WorkspaceManager workspaces,
            ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.workspaces = workspaces;
        this.objectMapper = objectMapper;
    }

    public byte[] exportAll() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            LearnerSnapshot learner = readCurrentLearner(connection);
            long learnerId = currentLearnerId(connection);
            ArrayList<JourneySnapshot> snapshots = new ArrayList<JourneySnapshot>();
            LinkedHashMap<String, byte[]> files = new LinkedHashMap<String, byte[]>();
            List<Map<String, Object>> sourceJourneys = readRows(connection, "journey", "learner_id = ?", learnerId);
            String currentJourneyPortableId = null;
            for (Map<String, Object> journey : sourceJourneys) {
                if (number(journey.get("is_current")) != 0) {
                    if (currentJourneyPortableId != null) {
                        throw new IllegalStateException("Learner 同时选择了多个当前 Journey");
                    }
                    currentJourneyPortableId = string(journey.get("portable_id"));
                }
                JourneySnapshot snapshot = exportJourney(connection, journey, files);
                snapshots.add(snapshot);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                writeEntry(zip, MANIFEST_ENTRY,
                        objectMapper.writeValueAsBytes(new TransferManifest(
                                FORMAT_VERSION, learner, currentJourneyPortableId, snapshots)));
                for (Map.Entry<String, byte[]> file : files.entrySet()) {
                    writeEntry(zip, file.getKey(), file.getValue());
                }
            }
            if (output.size() > MAX_ARCHIVE_BYTES) {
                throw new IllegalArgumentException("导出文件超过 128 MB，同步包不能导入");
            }
            connection.commit();
            return output.toByteArray();
        } catch (IOException | SQLException error) {
            throw new IllegalStateException("无法导出 Journey 数据", error);
        }
    }

    public ImportPreview preview(byte[] archive) {
        ParsedArchive parsed = parseArchive(archive);
        try (Connection connection = dataSource.getConnection()) {
            ArrayList<JourneyConflict> conflicts = new ArrayList<JourneyConflict>();
            for (JourneySnapshot journey : parsed.manifest().journeys()) {
                findTarget(connection, journey.portableId()).ifPresent(target -> conflicts.add(
                        new JourneyConflict(journey.portableId(), target.goalDescription())));
            }
            String importedCurrentGoal = parsed.manifest().journeys().stream()
                    .filter(journey -> samePortableId(
                            journey.portableId(), parsed.manifest().currentJourneyPortableId()))
                    .map(journey -> string(journey.journey().get("goal_description")))
                    .findFirst().orElse(null);
            return new ImportPreview(parsed.manifest().journeys().size(),
                    parsed.manifest().journeys().size() - conflicts.size(), conflicts,
                    currentLearnerIdOrNull(connection) != null,
                    parsed.manifest().learner().displayName(), importedCurrentGoal);
        } catch (SQLException error) {
            throw new IllegalStateException("无法检查 Journey 导入冲突", error);
        }
    }

    public ImportResult importAll(byte[] archive, boolean replaceConflicts) {
        ParsedArchive parsed = parseArchive(archive);
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("PRAGMA foreign_keys = ON");
            connection.setAutoCommit(false);
            ArrayList<WorkspaceReplacement> replacements = new ArrayList<WorkspaceReplacement>();
            try {
                long learnerId = importLearner(connection, parsed.manifest().learner());
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE journey SET is_current = 0 WHERE learner_id = ?")) {
                    statement.setLong(1, learnerId);
                    statement.executeUpdate();
                }
                ArrayList<String> added = new ArrayList<String>();
                ArrayList<String> replaced = new ArrayList<String>();
                Map<String, JourneyTarget> targets = new HashMap<String, JourneyTarget>();

                for (JourneySnapshot snapshot : parsed.manifest().journeys()) {
                    java.util.Optional<JourneyTarget> existing = findTarget(connection, snapshot.portableId());
                    if (existing.isPresent() && !replaceConflicts) {
                        throw new OverwriteConfirmationRequired();
                    }
                    JourneyTarget target = existing.orElse(null);
                    if (target != null) {
                        queueWorkspace(replacements, workspaces.learningWorkspace(target.id()), Map.of());
                    }
                    targets.put(snapshot.portableId(), target);
                }
                for (JourneySnapshot snapshot : parsed.manifest().journeys()) {
                    JourneyTarget target = targets.get(snapshot.portableId());
                    long journeyId = target == null
                            ? insertJourney(connection, learnerId, snapshot)
                            : target.id();
                    Long learningJourneyId = null;
                    if (target != null && target.learningJourneyId() != null) {
                        deleteLearningData(connection, target.learningJourneyId());
                    }
                    if (snapshot.learningJourney() != null) {
                        learningJourneyId = importLearningJourney(connection, learnerId, snapshot);
                        importTables(connection, learningJourneyId, snapshot.tables());
                    }
                    updateJourney(connection, journeyId, snapshot, learningJourneyId,
                            samePortableId(snapshot.portableId(), parsed.manifest().currentJourneyPortableId()));
                    if (target == null) {
                        added.add(string(snapshot.journey().get("goal_description")));
                    } else {
                        replaced.add(string(snapshot.journey().get("goal_description")));
                    }

                    queueWorkspace(replacements, workspaces.learningWorkspace(journeyId),
                            archiveWorkspaceFiles(parsed, snapshot));
                }

                for (WorkspaceReplacement replacement : replacements) {
                    replacement.previousFiles(readWorkspace(replacement.workspace()));
                }
                try {
                    for (WorkspaceReplacement replacement : replacements) {
                        replacement.applied(true);
                        workspaces.replaceFiles(replacement.workspace(), replacement.files());
                    }
                    connection.commit();
                } catch (IOException | SQLException | RuntimeException error) {
                    connection.rollback();
                    restoreWorkspaces(replacements, error);
                    throw error;
                }
                return new ImportResult(added, replaced);
            } catch (OverwriteConfirmationRequired error) {
                connection.rollback();
                throw error;
            } catch (IllegalArgumentException error) {
                connection.rollback();
                throw error;
            } catch (SQLException | IOException error) {
                connection.rollback();
                throw new IllegalArgumentException("导入包中的数据无法应用，原数据未更改", error);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("无法导入 Journey 数据", error);
        }
    }

    private JourneySnapshot exportJourney(
            Connection connection,
            Map<String, Object> sourceJourney,
            Map<String, byte[]> files) throws SQLException, IOException {
        String portableId = string(sourceJourney.get("portable_id"));
        long journeyId = number(sourceJourney.get("id"));
        Object sourceLearningJourneyId = sourceJourney.get("learning_journey_id");
        Map<String, Object> learningJourney = sourceLearningJourneyId == null
                ? null
                : readOne(connection, "learning_journey", "id", number(sourceLearningJourneyId));
        if (sourceLearningJourneyId != null && learningJourney == null) {
            throw new IllegalStateException("Journey 的 LearningJourney 记录缺失");
        }
        LinkedHashMap<String, List<Map<String, Object>>> tables = emptyTables();
        if (learningJourney != null) {
            long learningId = number(learningJourney.get("id"));
            tables.put("chapter", readRows(connection, "chapter", "journey_id = ?", learningId));
            tables.put("learn_unit", readRows(connection, "learn_unit", "journey_id = ?", learningId));
            tables.put("learning_path_item", readRows(connection, "learning_path_item", "journey_id = ?", learningId));
            tables.put("practice_task", readRows(connection, "practice_task", "journey_id = ?", learningId));
            tables.put("practice_attempt", readRows(connection, "practice_attempt",
                    "practice_task_id IN (SELECT id FROM practice_task WHERE journey_id = ?)", learningId));
            tables.put("practice_evidence", readRows(connection, "practice_evidence",
                    "attempt_id IN (SELECT a.id FROM practice_attempt a JOIN practice_task t "
                            + "ON t.id = a.practice_task_id WHERE t.journey_id = ?)", learningId));
            tables.put("practice_assessment", readRows(connection, "practice_assessment", "journey_id = ?", learningId));
        }

        long targetId = journeyId;
        Workspace learningWorkspace = workspaces.learningWorkspace(targetId);
        List<String> learningFiles = archiveWorkspace(files, portableId, learningWorkspace);
        return new JourneySnapshot(
                portableId,
                strip(sourceJourney, Set.of("id", "portable_id", "learner_id", "learning_journey_id", "is_current")),
                strip(learningJourney, Set.of("learner_id")),
                tables,
                learningFiles);
    }

    private LearnerSnapshot readCurrentLearner(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT display_name, background_summary, created_at
                FROM learner ORDER BY id LIMIT 1
                """);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new IllegalArgumentException("请先设置 Learner，再上传或导出学习数据");
            }
            return new LearnerSnapshot(result.getString("display_name"),
                    result.getString("background_summary"), result.getString("created_at"));
        }
    }

    private long importLearner(Connection connection, LearnerSnapshot learner) throws SQLException {
        Long existingId = currentLearnerIdOrNull(connection);
        if (existingId == null) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO learner(display_name, background_summary, created_at) VALUES (?, ?, ?)
                    """)) {
                statement.setString(1, learner.displayName());
                statement.setString(2, learner.backgroundSummary());
                statement.setString(3, learner.createdAt());
                statement.executeUpdate();
                return lastInsertedId(connection);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE learner SET display_name = ?, background_summary = ?, created_at = ? WHERE id = ?
                """)) {
            statement.setString(1, learner.displayName());
            statement.setString(2, learner.backgroundSummary());
            statement.setString(3, learner.createdAt());
            statement.setLong(4, existingId);
            statement.executeUpdate();
        }
        return existingId;
    }

    private List<String> archiveWorkspace(
            Map<String, byte[]> destination,
            String portableId,
            Workspace workspace) throws IOException {
        Path root = workspace.root();
        if (Files.notExists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<WorkspaceFileEntry> workspaceFiles = WorkspaceIgnoreRules.filter(root, workspaces.listFiles(workspace));
        ArrayList<String> paths = new ArrayList<String>();
        for (WorkspaceFileEntry entry : workspaceFiles) {
            String archivePath = workspaceEntry(portableId, entry.path());
            if (destination.putIfAbsent(archivePath, workspaces.readBytes(workspace, entry.path())) != null) {
                throw new IllegalArgumentException("Workspace 中存在重复的迁移路径");
            }
            paths.add(entry.path());
        }
        return List.copyOf(paths);
    }

    private ParsedArchive parseArchive(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_ARCHIVE_BYTES) {
            throw new IllegalArgumentException("导入文件为空或超过 128 MB");
        }
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entries.size() >= MAX_ARCHIVE_ENTRIES || entry.isDirectory()
                        || entries.containsKey(entry.getName())) {
                    throw new IllegalArgumentException("导入文件包含重复或无效的文件项");
                }
                long limit = MANIFEST_ENTRY.equals(entry.getName()) ? 16L * 1024 * 1024 : MAX_WORKSPACE_FILE_BYTES;
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = zip.read(buffer)) >= 0) {
                    total += count;
                    if (content.size() + count > limit || total > MAX_ARCHIVE_BYTES) {
                        throw new IllegalArgumentException("导入文件超过允许大小");
                    }
                    content.write(buffer, 0, count);
                }
                entries.put(entry.getName(), content.toByteArray());
                zip.closeEntry();
            }
            byte[] manifestBytes = entries.remove(MANIFEST_ENTRY);
            if (manifestBytes == null) {
                throw new IllegalArgumentException("导入文件缺少 Journey 清单");
            }
            TransferManifest manifest = objectMapper.readValue(manifestBytes, TransferManifest.class);
            validateManifest(manifest, entries);
            return new ParsedArchive(manifest, entries);
        } catch (IOException error) {
            throw new IllegalArgumentException("导入文件格式无效", error);
        }
    }

    private void validateManifest(TransferManifest manifest, Map<String, byte[]> files) {
        if (manifest == null || manifest.formatVersion() != FORMAT_VERSION || manifest.journeys() == null
                || manifest.journeys().size() > MAX_JOURNEYS || manifest.learner() == null) {
            throw new IllegalArgumentException("导入文件版本不受支持或内容无效");
        }
        if (manifest.learner().displayName() == null || manifest.learner().displayName().isBlank()
                || manifest.learner().backgroundSummary() == null
                || manifest.learner().backgroundSummary().isBlank()) {
            throw new IllegalArgumentException("Learner 资料不完整");
        }
        try {
            Instant.parse(manifest.learner().createdAt());
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Learner 创建时间无效", error);
        }
        String currentPortableId = manifest.currentJourneyPortableId();
        if (currentPortableId != null && !isUuid(currentPortableId)) {
            throw new IllegalArgumentException("当前 Journey 标识无效");
        }
        HashSet<String> portableIds = new HashSet<String>();
        HashSet<String> expectedFiles = new HashSet<String>();
        int totalRows = 0;
        for (JourneySnapshot journey : manifest.journeys()) {
            if (journey == null || !isUuid(journey.portableId())
                    || !portableIds.add(normalizedUuid(journey.portableId()))
                    || journey.journey() == null || journey.tables() == null
                    || journey.learningFiles() == null) {
                throw new IllegalArgumentException("Journey 清单包含无效或重复的唯一标识");
            }
            requireKeys(journey.journey(), Set.of("goal_description", "status", "created_at", "archived_at"));
            if (journey.learningJourney() != null) {
                requireKeys(journey.learningJourney(), Set.copyOf(LEARNING_JOURNEY_COLUMNS));
                if (number(journey.learningJourney().get("id")) <= 0) {
                    throw new IllegalArgumentException("LearningJourney ID 无效");
                }
            } else if (journey.tables().values().stream().anyMatch(rows -> rows != null && !rows.isEmpty())) {
                throw new IllegalArgumentException("没有 LearningJourney 的 Journey 不能包含学习记录");
            }
            if (!"ACTIVE".equals(journey.journey().get("status"))
                    && !"ARCHIVED".equals(journey.journey().get("status"))) {
                throw new IllegalArgumentException("Journey 状态无效");
            }
            if (samePortableId(currentPortableId, journey.portableId())
                    && !"ACTIVE".equals(journey.journey().get("status"))) {
                throw new IllegalArgumentException("已归档的 Journey 不能是当前 Journey");
            }
            if (!journey.tables().keySet().equals(Set.copyOf(TABLES))) {
                throw new IllegalArgumentException("Journey 清单中的数据表不完整");
            }
            for (String table : TABLES) {
                List<Map<String, Object>> rows = journey.tables().get(table);
                if (rows == null || rows.size() > MAX_ROWS_PER_TABLE) {
                    throw new IllegalArgumentException("Journey 表记录数无效: " + table);
                }
                HashSet<Long> ids = new HashSet<Long>();
                for (Map<String, Object> row : rows) {
                    totalRows += 1;
                    if (row == null || !row.keySet().equals(Set.copyOf(TABLE_COLUMNS.get(table)))
                            || totalRows > MAX_TOTAL_ROWS || number(row.get("id")) <= 0
                            || !ids.add(number(row.get("id")))) {
                        throw new IllegalArgumentException("Journey 表记录无效: " + table);
                    }
                }
            }
            validatePaths(journey.portableId(), journey.learningFiles(), expectedFiles);
        }
        if (currentPortableId != null && portableIds.stream()
                .noneMatch(portableId -> samePortableId(portableId, currentPortableId))) {
            throw new IllegalArgumentException("当前 Journey 不在传输包中");
        }
        if (!expectedFiles.equals(files.keySet())) {
            throw new IllegalArgumentException("导入文件中的 Workspace 文件与清单不匹配");
        }
    }

    private void validatePaths(String portableId, List<String> paths, Set<String> expectedFiles) {
        HashSet<String> unique = new HashSet<String>();
        for (String path : paths) {
            if (!isSafeRelativePath(path) || !unique.add(path)
                    || !expectedFiles.add(workspaceEntry(portableId, path))) {
                throw new IllegalArgumentException("Workspace 路径无效或重复");
            }
        }
    }

    private Map<String, byte[]> archiveWorkspaceFiles(ParsedArchive archive, JourneySnapshot snapshot) {
        List<String> paths = snapshot.learningFiles();
        LinkedHashMap<String, byte[]> result = new LinkedHashMap<String, byte[]>();
        for (String path : paths) {
            result.put(path, archive.files().get(workspaceEntry(snapshot.portableId(), path)));
        }
        return result;
    }

    private long insertJourney(Connection connection, long learnerId, JourneySnapshot snapshot) throws SQLException {
        Map<String, Object> journey = snapshot.journey();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO journey(portable_id, learner_id, goal_description, status, created_at, archived_at,
                    learning_journey_id, is_current)
                VALUES (?, ?, ?, ?, ?, ?, NULL, 0)
                """)) {
            statement.setString(1, normalizedUuid(snapshot.portableId()));
            statement.setLong(2, learnerId);
            statement.setString(3, string(journey.get("goal_description")));
            statement.setString(4, string(journey.get("status")));
            statement.setString(5, string(journey.get("created_at")));
            statement.setString(6, nullableString(journey.get("archived_at")));
            statement.executeUpdate();
            return lastInsertedId(connection);
        }
    }

    private long importLearningJourney(
            Connection connection,
            long learnerId,
            JourneySnapshot snapshot) throws SQLException {
        LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>(snapshot.learningJourney());
        row.put("learner_id", learnerId);
        return insertRow(connection, "learning_journey",
                List.of("learner_id", "language_pack_id", "title", "status", "created_at", "completed_at"),
                row, Map.of());
    }

    private void importTables(
            Connection connection,
            long learningJourneyId,
            Map<String, List<Map<String, Object>>> tables) throws SQLException {
        Map<Long, Long> chapters = insertRows(connection, "chapter", tables.get("chapter"),
                row -> Map.of("journey_id", learningJourneyId));
        Map<Long, Long> units = insertRows(connection, "learn_unit", tables.get("learn_unit"),
                row -> Map.of("journey_id", learningJourneyId,
                        "chapter_id", mapped(chapters, row.get("chapter_id"), "chapter")));
        Map<Long, Long> tasks = insertRows(connection, "practice_task", tables.get("practice_task"),
                row -> Map.of("journey_id", learningJourneyId,
                        "learn_unit_id", mapped(units, row.get("learn_unit_id"), "LearnUnit")));
        Map<Long, Long> attempts = insertRows(connection, "practice_attempt", tables.get("practice_attempt"),
                row -> Map.of("practice_task_id", mapped(tasks, row.get("practice_task_id"), "PracticeTask")));
        insertRows(connection, "practice_evidence", tables.get("practice_evidence"),
                row -> Map.of("attempt_id", mapped(attempts, row.get("attempt_id"), "PracticeAttempt")));
        Map<Long, Long> assessments = insertRows(connection, "practice_assessment",
                tables.get("practice_assessment"),
                row -> Map.of("journey_id", learningJourneyId,
                        "learn_unit_id", mapped(units, row.get("learn_unit_id"), "LearnUnit"),
                        "practice_task_id", mapped(tasks, row.get("practice_task_id"), "PracticeTask"),
                        "practice_attempt_id", mapped(attempts, row.get("practice_attempt_id"), "PracticeAttempt")));
        insertRows(connection, "learning_path_item", tables.get("learning_path_item"),
                row -> {
                    LinkedHashMap<String, Object> values = new LinkedHashMap<String, Object>();
                    values.put("journey_id", learningJourneyId);
                    values.put("learn_unit_id", mapped(units, row.get("learn_unit_id"), "LearnUnit"));
                    values.put("assessment_id", row.get("assessment_id") == null ? null
                            : mapped(assessments, row.get("assessment_id"), "PracticeAssessment"));
                    return values;
                });
    }

    private Map<Long, Long> insertRows(
            Connection connection,
            String table,
            List<Map<String, Object>> rows,
            RowOverrides overrides) throws SQLException {
        HashMap<Long, Long> ids = new HashMap<Long, Long>();
        List<String> insertColumns = TABLE_COLUMNS.get(table).stream().filter(column -> !"id".equals(column)).toList();
        for (Map<String, Object> row : rows) {
            long sourceId = number(row.get("id"));
            long targetId = insertRow(connection, table, insertColumns, row, overrides.apply(row));
            ids.put(sourceId, targetId);
        }
        return ids;
    }

    private long insertRow(
            Connection connection,
            String table,
            List<String> columns,
            Map<String, Object> row,
            Map<String, Object> overrides) throws SQLException {
        String sql = "INSERT INTO " + table + "(" + String.join(",", columns) + ") VALUES ("
                + String.join(",", java.util.Collections.nCopies(columns.size(), "?")) + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < columns.size(); index++) {
                String column = columns.get(index);
                statement.setObject(index + 1, overrides.containsKey(column) ? overrides.get(column) : row.get(column));
            }
            statement.executeUpdate();
            return lastInsertedId(connection);
        }
    }

    private void updateJourney(
            Connection connection,
            long journeyId,
            JourneySnapshot snapshot,
            Long learningJourneyId,
            boolean isCurrent) throws SQLException {
        Map<String, Object> journey = snapshot.journey();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE journey SET goal_description = ?, status = ?, created_at = ?, archived_at = ?,
                    learning_journey_id = ?, is_current = ? WHERE id = ?
                """)) {
            statement.setString(1, string(journey.get("goal_description")));
            statement.setString(2, string(journey.get("status")));
            statement.setString(3, string(journey.get("created_at")));
            statement.setString(4, nullableString(journey.get("archived_at")));
            if (learningJourneyId == null) {
                statement.setObject(5, null);
            } else {
                statement.setLong(5, learningJourneyId);
            }
            statement.setInt(6, isCurrent && "ACTIVE".equals(journey.get("status")) ? 1 : 0);
            statement.setLong(7, journeyId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("目标 Journey 已不存在");
            }
        }
    }

    private void deleteLearningData(Connection connection, long learningJourneyId) throws SQLException {
        delete(connection, "learning_path_item", "journey_id", learningJourneyId);
        delete(connection, "practice_assessment", "journey_id", learningJourneyId);
        delete(connection, "practice_task", "journey_id", learningJourneyId);
        delete(connection, "learn_unit", "journey_id", learningJourneyId);
        delete(connection, "chapter", "journey_id", learningJourneyId);
        delete(connection, "learning_journey", "id", learningJourneyId);
    }

    private void delete(Connection connection, String table, String column, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE " + column + " = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
    }

    private long currentLearnerId(Connection connection) throws SQLException {
        Long learnerId = currentLearnerIdOrNull(connection);
        if (learnerId == null) {
            throw new IllegalArgumentException("请先设置 Learner，再导入学习数据");
        }
        return learnerId;
    }

    private Long currentLearnerIdOrNull(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM learner ORDER BY id LIMIT 1");
             ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getLong("id") : null;
        }
    }

    private java.util.Optional<JourneyTarget> findTarget(Connection connection, String portableId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, learning_journey_id, goal_description "
                        + "FROM journey WHERE lower(portable_id) = ?")) {
            statement.setString(1, normalizedUuid(portableId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return java.util.Optional.empty();
                }
                long learningJourneyId = result.getLong("learning_journey_id");
                boolean noLearningJourney = result.wasNull();
                return java.util.Optional.of(new JourneyTarget(
                        result.getLong("id"), noLearningJourney ? null : learningJourneyId,
                        result.getString("goal_description")));
            }
        }
    }

    private Map<String, Object> readOne(Connection connection, String table, String key, long id) throws SQLException {
        List<Map<String, Object>> rows = readRows(connection, table, key + " = ?", id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<Map<String, Object>> readRows(Connection connection, String table, String predicate, long id)
            throws SQLException {
        ArrayList<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        String query = "SELECT * FROM " + table + " WHERE " + predicate + " ORDER BY id";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            if (!"1 = 1".equals(predicate)) {
                statement.setLong(1, id);
            }
            try (ResultSet result = statement.executeQuery()) {
                java.sql.ResultSetMetaData metadata = result.getMetaData();
                while (result.next()) {
                    LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
                    for (int column = 1; column <= metadata.getColumnCount(); column++) {
                        row.put(metadata.getColumnLabel(column), result.getObject(column));
                    }
                    rows.add(row);
                }
            }
        }
        return List.copyOf(rows);
    }

    private long lastInsertedId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT last_insert_rowid()")) {
            result.next();
            return result.getLong(1);
        }
    }

    private LinkedHashMap<String, List<Map<String, Object>>> emptyTables() {
        LinkedHashMap<String, List<Map<String, Object>>> result = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : TABLES) {
            result.put(table, List.of());
        }
        return result;
    }

    private Map<String, Object> strip(Map<String, Object> row, Set<String> omitted) {
        if (row == null) {
            return null;
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>(row);
        omitted.forEach(result::remove);
        return result;
    }

    private void queueWorkspace(List<WorkspaceReplacement> replacements, Workspace workspace,
                                Map<String, byte[]> files) {
        WorkspaceReplacement existing = replacements.stream()
                .filter(item -> item.workspace().root().equals(workspace.root())).findFirst().orElse(null);
        if (existing == null) {
            replacements.add(new WorkspaceReplacement(workspace, new LinkedHashMap<String, byte[]>(files)));
        } else {
            existing.files().clear();
            existing.files().putAll(files);
        }
    }

    private Map<String, byte[]> readWorkspace(Workspace workspace) throws IOException {
        if (Files.notExists(workspace.root(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return Map.of();
        }
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<String, byte[]>();
        for (com.db117.learnagent.workspace.domain.WorkspaceFileEntry file : workspaces.listFiles(workspace)) {
            files.put(file.path(), workspaces.readBytes(workspace, file.path()));
        }
        return files;
    }

    private void restoreWorkspaces(List<WorkspaceReplacement> replacements, Throwable cause) {
        for (WorkspaceReplacement replacement : replacements) {
            if (!replacement.applied()) {
                continue;
            }
            try {
                workspaces.replaceFiles(replacement.workspace(), replacement.previousFiles());
            } catch (IOException | RuntimeException restoreError) {
                cause.addSuppressed(restoreError);
            }
        }
    }

    private long mapped(Map<Long, Long> ids, Object sourceId, String entity) {
        Long targetId = ids.get(number(sourceId));
        if (targetId == null) {
            throw new IllegalArgumentException("导入包引用了不存在的 " + entity);
        }
        return targetId;
    }

    private static boolean isUuid(String value) {
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return false;
        }
    }

    private static boolean samePortableId(String first, String second) {
        return first != null && second != null && first.equalsIgnoreCase(second);
    }

    private static String normalizedUuid(String value) {
        return UUID.fromString(value).toString();
    }

    private static boolean isSafeRelativePath(String value) {
        if (value == null || value.isBlank() || value.contains("\\") || value.startsWith("/")) {
            return false;
        }
        for (String part : value.split("/")) {
            if (part.isBlank() || ".".equals(part) || "..".equals(part)) {
                return false;
            }
        }
        return true;
    }

    private static String workspaceEntry(String portableId, String relativePath) {
        return "workspaces/" + portableId + "/learning/" + relativePath;
    }

    private static void requireKeys(Map<String, Object> value, Set<String> expected) {
        if (!value.keySet().equals(expected)) {
            throw new IllegalArgumentException("导入文件字段无效");
        }
    }

    private static long number(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("导入文件中的 ID 无效");
        }
        return number.longValue();
    }

    private static String string(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("导入文件中的文本字段无效");
        }
        return text;
    }

    private static String nullableString(Object value) {
        return value == null ? null : string(value);
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private interface RowOverrides {
        Map<String, Object> apply(Map<String, Object> row);
    }

    /** Journey 导入前提示用户的覆盖范围。
     *
     * @param journeyCount 文件中的 Journey 总数
     * @param additionCount 目标设备上尚不存在、导入后会新增的 Journey 数
     * @param conflicts 具有相同 portable ID、需要用户确认覆盖的 Journey
     * @param learnerWillBeReplaced 导入时是否会覆盖当前设备的 Learner 资料
     * @param importedLearnerDisplayName 传输包中的 Learner 显示名称
     * @param importedCurrentJourneyGoal 传输包中当前选中的 Journey 目标；没有选中值时为空
     */
    @RegisterForReflection
    public record ImportPreview(
            int journeyCount,
            int additionCount,
            List<JourneyConflict> conflicts,
            boolean learnerWillBeReplaced,
            String importedLearnerDisplayName,
            String importedCurrentJourneyGoal) {
        public ImportPreview {
            conflicts = List.copyOf(conflicts);
        }
    }

    /** 与导入包唯一标识相同的目标 Journey。
     *
     * @param portableId Journey 在设备间迁移时保留的 UUID
     * @param goalDescription 当前设备上的 Journey 目标，供确认覆盖时展示
     */
    @RegisterForReflection
    public record JourneyConflict(String portableId, String goalDescription) {
    }

    /** 导入后的 Journey 变更摘要。
     *
     * @param added 新增 Journey 的目标描述
     * @param replaced 被覆盖 Journey 的目标描述
     */
    @RegisterForReflection
    public record ImportResult(List<String> added, List<String> replaced) {
        public ImportResult {
            added = List.copyOf(added);
            replaced = List.copyOf(replaced);
        }
    }

    /** 压缩包顶层格式。
     *
     * @param formatVersion 迁移文件格式版本
     * @param learner 导出设备的 Learner 资料
     * @param currentJourneyPortableId 导出设备当前选中的 Journey 唯一标识；没有选择时为空
     * @param journeys 文件中包含的全部 Journey
     */
    @RegisterForReflection
    public record TransferManifest(
            int formatVersion,
            LearnerSnapshot learner,
            String currentJourneyPortableId,
            List<JourneySnapshot> journeys) {
    }

    /** 会随全部 Journey 一起传输的 Learner 资料。
     *
     * @param displayName Learner 显示名称
     * @param backgroundSummary 学习者背景与能力描述
     * @param createdAt Learner 首次创建时间
     */
    @RegisterForReflection
    public record LearnerSnapshot(String displayName, String backgroundSummary, String createdAt) {
    }

    /** 一个 Journey 的 Domain State 行和学习工作区文件清单。
     *
     * @param portableId 设备间匹配 Journey 使用的 UUID
     * @param journey Journey 自身的目标与生命周期字段
     * @param learningJourney LearningJourney 元数据；未生成学习路径时为空
     * @param tables LearningPathItem 与 Practice Domain 表记录
     * @param learningFiles LearningWorkspace 中随文件包传输的相对路径
     */
    @RegisterForReflection
    public record JourneySnapshot(
            String portableId,
            Map<String, Object> journey,
            Map<String, Object> learningJourney,
            Map<String, List<Map<String, Object>>> tables,
            List<String> learningFiles) {
    }

    /** 已完成格式与文件路径验证的导入包。
     *
     * @param manifest 版本化 Journey 数据清单
     * @param files 清单引用的受管 Workspace 文件字节
     */
    private record ParsedArchive(TransferManifest manifest, Map<String, byte[]> files) {
    }

    /** 目标设备上现有 Journey 的本地状态。
     *
     * @param id Journey 的本地 SQLite 主键
     * @param learningJourneyId 现有 LearningJourney 的本地 SQLite 主键；未规划时为空
     * @param goalDescription 当前设备上的 Journey 目标
     */
    private record JourneyTarget(long id, Long learningJourneyId, String goalDescription) {
    }

    private static final class WorkspaceReplacement {
        private final Workspace workspace;
        private final LinkedHashMap<String, byte[]> files;
        private Map<String, byte[]> previousFiles = Map.of();
        private boolean applied;

        private WorkspaceReplacement(Workspace workspace, LinkedHashMap<String, byte[]> files) {
            this.workspace = workspace;
            this.files = files;
        }

        private Workspace workspace() {
            return workspace;
        }

        private LinkedHashMap<String, byte[]> files() {
            return files;
        }

        private Map<String, byte[]> previousFiles() {
            return previousFiles;
        }

        private void previousFiles(Map<String, byte[]> value) {
            previousFiles = value;
        }

        private boolean applied() {
            return applied;
        }

        private void applied(boolean value) {
            applied = value;
        }
    }

    public static final class OverwriteConfirmationRequired extends IllegalArgumentException {
        public OverwriteConfirmationRequired() {
            super("存在相同唯一标识的 Journey，需要先确认覆盖");
        }
    }
}
