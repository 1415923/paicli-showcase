package com.paicli.benchmark.stress;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.model.AgentMessage;
import com.paicli.runtime.InstructionManifestRuntime;
import com.paicli.runtime.RuntimeManifest;
import com.paicli.runtime.TaskRecoveryService;
import com.paicli.state.R1aStoreResults.OwnershipResult;
import com.paicli.state.StateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Repeated contention through distinct StateStore connections targeting one SQLite DB. */
class AutumnSharedDatabaseStressIT {
    @TempDir Path workspace;

    @Test
    void twoStateStoresContendForOneOwnership() throws Exception {
        int iterations = Integer.getInteger("paicli.stress.iterations", 25);
        if (iterations < 1 || iterations > 1000) {
            throw new IllegalArgumentException("paicli.stress.iterations must be 1..1000");
        }
        Files.writeString(workspace.resolve("AGENTS.md"), "bounded shared-db stress fixture\n");
        int success = 0;
        int typedRejections = 0;
        int sqliteBusy = 0;
        int violations = 0;
        Map<String, Integer> rejectionStatuses = new java.util.TreeMap<>();
        long started = System.nanoTime();

        try (StateStore setup = new StateStore(workspace);
             StateStore left = new StateStore(workspace);
             StateStore right = new StateStore(workspace);
             var executor = Executors.newFixedThreadPool(2)) {
            Config config = config();
            InstructionManifestRuntime admission = new InstructionManifestRuntime(setup, workspace);

            for (int index = 0; index < iterations; index++) {
                int iterationIndex = index;
                CanonicalId taskId = CanonicalId.newId(EntityFamily.TASK);
                var root = admission.admitRoot(taskId, "shared-db-race-" + index, 10_000,
                        config, RuntimeManifest.disabled(), Instant.now());
                setup.checkpoints.save(taskId.value(), 1, "RUNNING",
                        java.util.List.of(AgentMessage.userGoal("shared-db-race-" + index)));
                Instant acquiredAt = Instant.now();
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);
                var a = executor.submit(() -> recover(left, ready, go, taskId,
                        "owner-a", "lease-a-" + iterationIndex, acquiredAt));
                var b = executor.submit(() -> recover(right, ready, go, taskId,
                        "owner-b", "lease-b-" + iterationIndex, acquiredAt));
                assertTrue(ready.await(10, TimeUnit.SECONDS), "contenders did not become ready");
                go.countDown();
                RecoveryOutcome first = a.get(30, TimeUnit.SECONDS);
                RecoveryOutcome second = b.get(30, TimeUnit.SECONDS);
                int winners = first.successes() + second.successes();
                int losers = first.typedRejections() + second.typedRejections();
                int busy = first.sqliteBusy() + second.sqliteBusy();
                success += winners;
                typedRejections += losers;
                sqliteBusy += busy;
                if (first.rejectionStatus() != null) {
                    rejectionStatuses.merge(first.rejectionStatus(), 1, Integer::sum);
                }
                if (second.rejectionStatus() != null) {
                    rejectionStatuses.merge(second.rejectionStatus(), 1, Integer::sum);
                }
                if (winners != 1 || losers != 1 || busy != 0
                        || setup.r1aDurable.readOwnership(taskId).isEmpty()) {
                    violations++;
                }
                var ownership = setup.r1aDurable.readOwnership(taskId).orElseThrow();
                String losingOwner = ownership.ownerInstanceId().equals("owner-a")
                        ? "owner-b" : "owner-a";
                assertEquals(OwnershipResult.OWNERSHIP_CONFLICT,
                        setup.r1aDurable.validateExecutionAuthority(taskId, losingOwner,
                                root.initialRun().id(), "not-the-winning-lease",
                                ownership.fencingToken(), ownership.casVersion(), acquiredAt));
            }
        }

        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        writeReport(iterations, success, typedRejections, rejectionStatuses, sqliteBusy,
                violations, elapsedMillis);
        assertEquals(iterations, success);
        assertEquals(iterations, typedRejections);
        assertEquals(0, violations);
    }

    private RecoveryOutcome recover(StateStore store, CountDownLatch ready, CountDownLatch go,
            CanonicalId taskId, String owner, String lease, Instant at) throws Exception {
        ready.countDown();
        assertTrue(go.await(10, TimeUnit.SECONDS));
        try {
            TaskRecoveryService.RecoveryResult result = new TaskRecoveryService(store,
                    new InstructionManifestRuntime(store, workspace)).recover(taskId, config(),
                    RuntimeManifest.disabled(), 10_000,
                    new TaskRecoveryService.LeaseRequest(owner, lease, at,
                            at.plus(Duration.ofMinutes(5)), Duration.ZERO));
            if (result instanceof TaskRecoveryService.Success) {
                return new RecoveryOutcome(1, 0, null, 0);
            }
            TaskRecoveryService.Rejected rejected = (TaskRecoveryService.Rejected) result;
            return new RecoveryOutcome(0, 1, rejected.status().name(), 0);
        } catch (java.sql.SQLException failure) {
            if (failure.getErrorCode() == 5
                    || failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("busy")) {
                return new RecoveryOutcome(0, 0, null, 1);
            }
            throw failure;
        }
    }

    private Config config() {
        return new Config(workspace.toString(), 30, 50_000, 200_000,
                Config.MODE_DENY, 10_000, Config.PROVIDER_MOCK, "model", null,
                null, "system", 3, "", 120, 50_000, 1,
                false, false, false);
    }

    private void writeReport(int iterations, int success, int typedRejections,
            Map<String, Integer> rejectionStatuses, int sqliteBusy, int violations,
            long elapsedMillis) throws Exception {
        String configured = System.getProperty("paicli.stress.report");
        if (configured == null || configured.isBlank()) return;
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
                .resolve("target").resolve("autumn-evidence");
        Path report = Path.of(configured).toAbsolutePath().normalize();
        if (!report.startsWith(root)) {
            throw new IllegalArgumentException("stress report must be under target/autumn-evidence");
        }
        Files.createDirectories(report.getParent());
        if (Files.exists(report)) throw new IllegalStateException("stress report already exists");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", "stress-result-v1");
        value.put("scenario", "double-recover-ownership");
        value.put("databaseMode", "SHARED_DB");
        value.put("iterations", iterations);
        value.put("ownershipSuccesses", success);
        value.put("typedLoserRejections", typedRejections);
        value.put("rejectionStatuses", rejectionStatuses);
        value.put("sqliteBusyFailures", sqliteBusy);
        value.put("invariantViolations", violations);
        value.put("duplicateReplayCount", null);
        value.put("duplicateReplayVerdict", "NOT_APPLICABLE");
        value.put("elapsedMillis", elapsedMillis);
        value.put("candidateIdentity", System.getProperty(
                "paicli.stress.candidate", "UNCOMMITTED_WORKTREE"));
        value.put("javaVersion", System.getProperty("java.version"));
        value.put("osName", System.getProperty("os.name"));
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(report.toFile(), value);
    }

    private record RecoveryOutcome(int successes, int typedRejections,
                                   String rejectionStatus, int sqliteBusy) { }
}
