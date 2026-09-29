package com.db117.learnagent.transfer;

import com.db117.learnagent.config.RuntimeConfig;
import com.db117.learnagent.learning.domain.Journey;
import com.db117.learnagent.learning.domain.Learner;
import com.db117.learnagent.persistence.sqlite.SqliteJourneyRepository;
import com.db117.learnagent.persistence.sqlite.SqliteLearnerRepository;
import com.db117.learnagent.persistence.sqlite.SqliteSchemaInitializer;
import com.db117.learnagent.transfer.application.JourneyTransferService;
import com.db117.learnagent.transfer.application.JourneyTransferService.ImportPreview;
import com.db117.learnagent.transfer.application.JourneyTransferService.OverwriteConfirmationRequired;
import com.db117.learnagent.transfer.application.R2SyncService;
import com.db117.learnagent.workspace.application.WorkspaceManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JourneyTransferSyncTest {
    private static final Instant CREATED_AT = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void objectStorageEndpointCannotEmbedCredentials() throws Exception {
        SQLiteDataSource dataSource = dataSource();
        try (Connection anchor = dataSource.getConnection()) {
            new SqliteSchemaInitializer(dataSource).initialize();
            R2SyncService service = new R2SyncService(dataSource);
            assertThrows(IllegalArgumentException.class, () -> service.save(
                    new R2SyncService.ConfigurationRequest(
                            "https://access:secret@objects.example.com", "auto", "learn-agent-data",
                            "access-key", "secret-key")));
        }
    }

    @Test
    void transferPackageOverwritesLearnerAndCurrentJourneyButKeepsLocalJourneys(@TempDir Path tempDirectory)
            throws Exception {
        SQLiteDataSource source = dataSource();
        SQLiteDataSource destination = dataSource();
        Path sourceData = tempDirectory.resolve("source");
        Path destinationData = tempDirectory.resolve("destination");
        try (Connection sourceAnchor = source.getConnection(); Connection destinationAnchor = destination.getConnection()) {
            new SqliteSchemaInitializer(source).initialize();
            new SqliteSchemaInitializer(destination).initialize();
            SqliteLearnerRepository sourceLearners = new SqliteLearnerRepository(source);
            SqliteJourneyRepository sourceJourneys = new SqliteJourneyRepository(source);
            Learner sourceLearner = sourceLearners.save(Learner.create("Alice", "TypeScript learner", CREATED_AT));
            Journey sourceJourney = sourceJourneys.save(Journey.create(sourceLearner.id(), "Learn R2 sync", CREATED_AT));
            sourceJourneys.selectCurrent(sourceJourney.id(), sourceLearner.id());
            WorkspaceManager sourceWorkspaces = workspaceManager(sourceData);
            com.db117.learnagent.workspace.domain.LearningWorkspace sourceWorkspace =
                    sourceWorkspaces.learningWorkspace(sourceJourney.id());
            sourceWorkspaces.writeFile(sourceWorkspace, ".env", "TOKEN=local");
            Files.createDirectories(tempDirectory.resolve(".git"));
            Files.writeString(tempDirectory.resolve(".gitignore"), "parent.secret\n");
            Files.writeString(sourceWorkspace.root().resolve(".gitignore"),
                    "*.tmp\n!keep.tmp\nsecret[0-9].txt\n**/deep.secret\nbuild/\n!build/\nbuild/private/\n");
            Files.writeString(sourceWorkspace.root().resolve("ignored.tmp"), "omit");
            Files.writeString(sourceWorkspace.root().resolve("keep.tmp"), "keep");
            Files.writeString(sourceWorkspace.root().resolve("parent.secret"), "omit");
            Files.writeString(sourceWorkspace.root().resolve("secret4.txt"), "omit");
            Files.createDirectories(sourceWorkspace.root().resolve("build/private"));
            Files.writeString(sourceWorkspace.root().resolve("build/visible.txt"), "keep");
            Files.writeString(sourceWorkspace.root().resolve("build/private/hidden.txt"), "omit");
            Files.createDirectories(sourceWorkspace.root().resolve("nested"));
            Files.createDirectories(sourceWorkspace.root().resolve("nested/.git"));
            Files.writeString(sourceWorkspace.root().resolve("nested/.gitignore"), "local.txt\nkeep.tmp\n");
            Files.writeString(sourceWorkspace.root().resolve("nested/local.txt"), "omit");
            Files.writeString(sourceWorkspace.root().resolve("nested/keep.tmp"), "omit by deeper rule");
            Files.writeString(sourceWorkspace.root().resolve("nested/deep.secret"), "omit");
            Files.writeString(sourceWorkspace.root().resolve("nested/parent.secret"), "inner repository");
            Files.writeString(sourceWorkspace.root().resolve("nested/visible.txt"), "keep");
            assertTrue(sourceWorkspaces.listFiles(sourceWorkspace).stream()
                    .anyMatch(file -> file.path().equals("ignored.tmp")));

            R2SyncService r2 = new R2SyncService(source);
            r2.save(new R2SyncService.ConfigurationRequest(
                    "https://objects.example.com/", "us-east-1", "learn-agent-data", "access-key", "secret-key"));
            assertTrue(r2.configuration().configured());
            assertEquals("https://objects.example.com", r2.configuration().endpoint());
            assertEquals("us-east-1", r2.configuration().region());
            assertFalse(r2.configuration().toString().contains("secret-key"));
            JourneyTransferService sourceTransfer = transferService(source, sourceWorkspaces);
            byte[] archive = sourceTransfer.exportAll();
            Set<String> archiveEntries = new HashSet<String>();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    archiveEntries.add(entry.getName());
                }
            }
            assertTrue(archiveEntries.stream().anyMatch(path -> path.endsWith("/.gitignore")));
            assertTrue(archiveEntries.stream().anyMatch(path -> path.endsWith("/keep.tmp")));
            assertTrue(archiveEntries.stream().anyMatch(path -> path.endsWith("/build/visible.txt")));
            assertTrue(archiveEntries.stream().anyMatch(path -> path.endsWith("/nested/visible.txt")));
            assertTrue(archiveEntries.stream().anyMatch(path -> path.endsWith("/nested/parent.secret")));
            assertFalse(archiveEntries.stream().anyMatch(path -> path.endsWith("/ignored.tmp")));
            assertFalse(archiveEntries.stream().anyMatch(path -> path.endsWith("/workspace/parent.secret")));
            assertFalse(archiveEntries.stream().anyMatch(path -> path.endsWith("/secret4.txt")));
            assertFalse(archiveEntries.stream().anyMatch(path -> path.endsWith("/build/private/hidden.txt")));
            assertFalse(archiveEntries.stream().anyMatch(path -> path.endsWith("/nested/local.txt")));
            assertFalse(archiveEntries.stream().anyMatch(path -> path.endsWith("/nested/keep.tmp")));
            assertTrue(archiveEntries.stream().anyMatch(path -> path.endsWith("/nested/deep.secret")));
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
                zip.getNextEntry();
                String manifest = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                assertFalse(manifest.contains("secret-key"));
                assertTrue(manifest.contains("Alice"));
            }

            SqliteLearnerRepository destinationLearners = new SqliteLearnerRepository(destination);
            SqliteJourneyRepository destinationJourneys = new SqliteJourneyRepository(destination);
            Learner oldLearner = destinationLearners.save(
                    Learner.create("Bob", "Old learner profile", CREATED_AT));
            Journey localJourney = destinationJourneys.save(
                    Journey.create(oldLearner.id(), "Local only", CREATED_AT));
            destinationJourneys.selectCurrent(localJourney.id(), oldLearner.id());
            WorkspaceManager destinationWorkspaces = workspaceManager(destinationData);
            JourneyTransferService destinationTransfer = transferService(destination, destinationWorkspaces);

            ImportPreview preview = destinationTransfer.preview(archive);
            assertEquals(1, preview.journeyCount());
            assertEquals(1, preview.additionCount());
            assertTrue(preview.learnerWillBeReplaced());
            assertEquals("Alice", preview.importedLearnerDisplayName());
            assertEquals("Learn R2 sync", preview.importedCurrentJourneyGoal());

            destinationTransfer.importAll(archive, false);

            Learner importedLearner = destinationLearners.findCurrent().orElseThrow();
            assertEquals(oldLearner.id(), importedLearner.id());
            assertEquals("Alice", importedLearner.displayName());
            assertEquals("TypeScript learner", importedLearner.backgroundSummary());
            List<Journey> importedJourneys = destinationJourneys.findByLearnerId(importedLearner.id());
            assertEquals(2, importedJourneys.size());
            assertTrue(importedJourneys.stream().anyMatch(journey -> journey.goalDescription().equals("Local only")));
            Journey current = destinationJourneys.findCurrentByLearnerId(importedLearner.id()).orElseThrow();
            assertEquals("Learn R2 sync", current.goalDescription());
            assertEquals("TOKEN=local", destinationWorkspaces.readFile(
                    destinationWorkspaces.learningWorkspace(current.id()), ".env").content());

            ImportPreview replacementPreview = destinationTransfer.preview(archive);
            assertEquals(1, replacementPreview.conflicts().size());
            assertEquals(0, replacementPreview.additionCount());
            assertThrows(OverwriteConfirmationRequired.class, () -> destinationTransfer.importAll(archive, false));
            Path importedWorkspaceRoot = destinationWorkspaces.learningWorkspace(current.id()).root();
            Files.writeString(importedWorkspaceRoot.resolve(".gitignore"), "local-only.tmp\n");
            Files.writeString(importedWorkspaceRoot.resolve("local-only.tmp"), "delete on full replace");
            destinationTransfer.importAll(archive, true);
            assertEquals(2, destinationJourneys.findByLearnerId(importedLearner.id()).size());
            assertFalse(Files.exists(importedWorkspaceRoot.resolve("local-only.tmp")));
        }
    }

    private SQLiteDataSource dataSource() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:transfer-sync-" + UUID.randomUUID() + "?mode=memory&cache=shared");
        return dataSource;
    }

    private WorkspaceManager workspaceManager(Path dataDirectory) {
        RuntimeConfig config = new RuntimeConfig() {
            @Override
            public String dataDir() {
                return dataDirectory.toString();
            }

            @Override
            public boolean memoryEnabled() {
                return false;
            }
        };
        return new WorkspaceManager(config);
    }

    private JourneyTransferService transferService(SQLiteDataSource dataSource, WorkspaceManager workspaces) {
        return new JourneyTransferService(dataSource, workspaces, new ObjectMapper());
    }
}
