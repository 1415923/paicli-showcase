package com.paicli.state;

import com.paicli.runtime.PersistenceSanitizer;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/** Durable, bounded state machine for one headless Core Demo run. */
public final class CoreDemoRunStore {
    public static final String PREPARING = "PREPARING";
    public static final String READY_FOR_WORKFLOW = "READY_FOR_WORKFLOW";
    public static final String WORKFLOW_RUNNING = "WORKFLOW_RUNNING";
    public static final String WORKFLOW_SUCCEEDED = "WORKFLOW_SUCCEEDED";
    public static final String WORKFLOW_FAILED = "WORKFLOW_FAILED";
    public static final String ARTIFACTS_WRITING = "ARTIFACTS_WRITING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String CLEANUP_PENDING = "CLEANUP_PENDING";
    public static final String CLEANED = "CLEANED";
    public static final String RETAINED_FOR_DIAGNOSTICS = "RETAINED_FOR_DIAGNOSTICS";
    public static final String RESUME_NOT_STARTED = "NOT_STARTED";
    public static final String RESUME_PENDING = "PENDING";
    public static final String RESUME_VALIDATING = "VALIDATING";
    public static final String RESUME_RUNNING = "RUNNING";
    public static final String RESUME_SUCCEEDED = "SUCCEEDED";
    public static final String RESUME_FAILED = "FAILED";
    public static final String REWIND_NOT_STARTED = "NOT_STARTED";
    public static final String REWIND_PENDING = "PENDING";
    public static final String REWIND_RUNNING = "RUNNING";
    public static final String REWIND_SUCCEEDED = "SUCCEEDED";
    public static final String REWIND_PARTIAL = "PARTIAL";
    public static final String REWIND_FAILED = "FAILED";

    private static final Set<String> PHASES = Set.of(
            "ALLOCATION", "EXTRACTION", "BASELINE", "PLAN", "CODE_INTELLIGENCE",
            "EXECUTE", "VERIFY", "REPAIR", "RESUME", "REWIND", "REPORT", "CLEANUP");
    private static final Set<String> ARTIFACTS =
            Set.of("NOT_STARTED", "STAGING", "COMMITTED", "FAILED");
    private static final Map<String, Set<String>> TRANSITIONS = Map.ofEntries(
            Map.entry(PREPARING, Set.of(READY_FOR_WORKFLOW, FAILED, RETAINED_FOR_DIAGNOSTICS)),
            Map.entry(READY_FOR_WORKFLOW, Set.of(WORKFLOW_RUNNING, FAILED)),
            Map.entry(WORKFLOW_RUNNING, Set.of(WORKFLOW_SUCCEEDED, WORKFLOW_FAILED, FAILED)),
            Map.entry(WORKFLOW_SUCCEEDED, Set.of(ARTIFACTS_WRITING)),
            Map.entry(WORKFLOW_FAILED, Set.of(ARTIFACTS_WRITING)),
            Map.entry(FAILED, Set.of(ARTIFACTS_WRITING, RETAINED_FOR_DIAGNOSTICS)),
            Map.entry(ARTIFACTS_WRITING, Set.of(COMPLETED, FAILED, RETAINED_FOR_DIAGNOSTICS)),
            Map.entry(COMPLETED, Set.of(CLEANUP_PENDING, CLEANED)),
            Map.entry(CLEANUP_PENDING, Set.of(CLEANED, RETAINED_FOR_DIAGNOSTICS))
    );

    private static final Pattern ABSOLUTE_PATH = Pattern.compile(
            "(?<![A-Za-z0-9])(?:[A-Za-z]:\\\\[^\\s]+|/(?:[^\\s/]+/)+[^\\s]*)");
    private final Connection connection;
    private final PersistenceSanitizer sanitizer = new PersistenceSanitizer();

    public CoreDemoRunStore(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    public void create(CreateRun run) throws SQLException {
        Objects.requireNonNull(run, "run");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO core_demo_runs(
                  run_id, scenario_id, fixture_version, fixture_manifest_hash,
                  seed_tree_hash, workspace_identity_hash, headless, provider_type,
                  status, current_phase, baseline_status, repair_count, resumed,
                  rewound, artifact_status
                ) VALUES (?, ?, ?, ?, ?, ?, 1, ?, 'PREPARING', 'ALLOCATION',
                          'NOT_RUN', 0, 0, 0, 'NOT_STARTED')
                """)) {
            statement.setString(1, id(run.runId(), "runId"));
            statement.setString(2, text(run.scenarioId(), "scenarioId", 128));
            statement.setString(3, text(run.fixtureVersion(), "fixtureVersion", 32));
            statement.setString(4, sha(run.fixtureManifestHash(), "fixtureManifestHash"));
            statement.setString(5, sha(run.seedTreeHash(), "seedTreeHash"));
            statement.setString(6, sha(run.workspaceIdentityHash(), "workspaceIdentityHash"));
            statement.setString(7, text(run.providerType(), "providerType", 64));
            statement.executeUpdate();
        }
    }

    public void recordPreparationPhase(String runId, String phase) throws SQLException {
        requirePhase(phase);
        if (!Set.of("ALLOCATION", "EXTRACTION", "BASELINE").contains(phase)) {
            throw new IllegalArgumentException("Not a preparation phase: " + phase);
        }
        updateFields(runId, PREPARING, "current_phase = ?", phase);
    }

    public void recordBaselinePassed(String runId, int tests, String treeHash)
            throws SQLException {
        if (tests < 1) throw new IllegalArgumentException("tests must be positive");
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE core_demo_runs SET status = 'READY_FOR_WORKFLOW',
                  current_phase = 'BASELINE', baseline_status = 'PASSED',
                  baseline_test_count = ?, baseline_tree_hash = ?,
                  updated_at = datetime('now')
                WHERE run_id = ? AND status = 'PREPARING'
                """)) {
            statement.setInt(1, tests);
            statement.setString(2, sha(treeHash, "baselineTreeHash"));
            statement.setString(3, runId);
            requireOne(statement, "Baseline pass rejected");
        }
    }

    public void recordBaselineFailed(String runId, int tests, String category, String error)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE core_demo_runs SET status = 'FAILED', current_phase = 'BASELINE',
                  baseline_status = 'FAILED', baseline_test_count = ?,
                  error_category = ?, bounded_error_excerpt = ?,
                  updated_at = datetime('now')
                WHERE run_id = ? AND status = 'PREPARING'
                """)) {
            statement.setInt(1, Math.max(0, tests));
            statement.setString(2, optional(category, 128));
            statement.setString(3, bounded(error));
            statement.setString(4, runId);
            requireOne(statement, "Baseline failure rejected");
        }
    }

    public void startWorkflow(String runId) throws SQLException {
        transition(runId, READY_FOR_WORKFLOW, WORKFLOW_RUNNING, "PLAN");
    }

    public void bindWorkflowIdentity(String runId, String taskId,
                                     String occurrenceKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE core_demo_runs SET task_id = ?, workflow_occurrence_key = ?,
                  updated_at = datetime('now')
                WHERE run_id = ? AND status = 'WORKFLOW_RUNNING'
                  AND task_id IS NULL AND workflow_occurrence_key IS NULL
                """)) {
            statement.setString(1, text(taskId, "taskId", 128));
            statement.setString(2, text(occurrenceKey, "occurrenceKey", 128));
            statement.setString(3, runId);
            requireOne(statement, "Workflow identity binding rejected");
        }
    }

    public void recordWorkflowPhase(String runId, String expectedPhase,
                                    String nextPhase) throws SQLException {
        requirePhase(expectedPhase);
        requirePhase(nextPhase);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE core_demo_runs SET current_phase = ?, updated_at = datetime('now')
                WHERE run_id = ? AND status = 'WORKFLOW_RUNNING'
                  AND current_phase = ?
                """)) {
            statement.setString(1, nextPhase);
            statement.setString(2, runId);
            statement.setString(3, expectedPhase);
            requireOne(statement, "Workflow phase update rejected");
        }
    }
    public void completeWorkflow(String runId, boolean success, String phase,
            String finalTreeHash, int repairCount, boolean resumed, boolean rewound,
            String errorCategory, String error) throws SQLException {
        requirePhase(phase);
        if (repairCount < 0 || repairCount > 5) {
            throw new IllegalArgumentException("repairCount is out of bounds");
        }
        String target = success ? WORKFLOW_SUCCEEDED : WORKFLOW_FAILED;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE core_demo_runs SET status = ?, current_phase = ?,
                  final_tree_hash = ?, repair_count = ?, resumed = ?, rewound = ?,
                  error_category = ?, bounded_error_excerpt = ?,
                  updated_at = datetime('now')
                WHERE run_id = ? AND status = 'WORKFLOW_RUNNING'
                """)) {
            statement.setString(1, target);
            statement.setString(2, phase);
            statement.setString(3, optionalSha(finalTreeHash, "finalTreeHash"));
            statement.setInt(4, repairCount);
            statement.setInt(5, resumed ? 1 : 0);
            statement.setInt(6, rewound ? 1 : 0);
            statement.setString(7, optional(errorCategory, 128));
            statement.setString(8, bounded(error));
            statement.setString(9, runId);
            requireOne(statement, "Workflow completion rejected");
        }
    }

    public void markPrimaryInterruptedAtCheckpoint(String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE core_demo_runs SET current_phase = 'RESUME',
                  resume_status = 'PENDING', updated_at = datetime('now')
                WHERE run_id = ? AND status = 'WORKFLOW_RUNNING'
                  AND current_phase = 'VERIFY' AND resume_status = 'NOT_STARTED'
                """)) {
            statement.setString(1, runId);
            requireOne(statement, "Primary checkpoint interruption rejected");
        }
    }

    public void beginResume(String runId, String occurrenceKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE core_demo_runs SET resume_status = 'VALIDATING',
                  resume_occurrence_key = ?, current_phase = 'RESUME',
                  updated_at = datetime('now')
                WHERE run_id = ? AND status = 'WORKFLOW_RUNNING'
                  AND current_phase = 'RESUME' AND resume_status = 'PENDING'
                  AND resume_occurrence_key IS NULL
                """)) {
            statement.setString(1, text(occurrenceKey, "resumeOccurrenceKey", 128));
            statement.setString(2, runId);
            requireOne(statement, "Resume ownership rejected");
        }
    }

    public void markResumeRunning(String runId, String occurrenceKey) throws SQLException {
        updateOwnedStage(runId, "resume_status", RESUME_VALIDATING, RESUME_RUNNING,
                "resume_occurrence_key", occurrenceKey, "RESUME");
    }

    public void markResumeSucceeded(String runId, String occurrenceKey,
            String finalTreeHash, int repairCount) throws SQLException {
        if (repairCount != 1) throw new IllegalArgumentException("Repair count must be one");
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE core_demo_runs SET resume_status = 'SUCCEEDED', resumed = 1,
                  final_tree_hash = ?, repair_count = ?, current_phase = 'REWIND',
                  rewind_status = 'PENDING', updated_at = datetime('now')
                WHERE run_id = ? AND status = 'WORKFLOW_RUNNING'
                  AND resume_status = 'RUNNING' AND resume_occurrence_key = ?
                  AND rewind_status = 'NOT_STARTED'
                """)) {
            statement.setString(1, sha(finalTreeHash, "finalTreeHash"));
            statement.setInt(2, repairCount);
            statement.setString(3, runId);
            statement.setString(4, occurrenceKey);
            requireOne(statement, "Resume completion rejected");
        }
    }

    public void markResumeFailed(String runId, String occurrenceKey,
            String category, String error) throws SQLException {
        failOwnedStage(runId, "resume_status", Set.of(RESUME_VALIDATING, RESUME_RUNNING),
                "resume_occurrence_key", occurrenceKey, "RESUME", category, error);
    }

    public void beginRewind(String runId, String occurrenceKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE core_demo_runs SET rewind_status = 'RUNNING',
                  rewind_occurrence_key = ?, current_phase = 'REWIND',
                  updated_at = datetime('now')
                WHERE run_id = ? AND status = 'WORKFLOW_RUNNING'
                  AND resume_status = 'SUCCEEDED' AND rewind_status = 'PENDING'
                  AND rewind_occurrence_key IS NULL
                """)) {
            statement.setString(1, text(occurrenceKey, "rewindOccurrenceKey", 128));
            statement.setString(2, runId);
            requireOne(statement, "Rewind ownership rejected");
        }
    }

    public void markRewindSucceeded(String runId, String occurrenceKey,
            String rewindTreeHash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE core_demo_runs SET rewind_status = 'SUCCEEDED', rewound = 1,
                  rewind_tree_hash = ?, current_phase = 'REWIND',
                  updated_at = datetime('now')
                WHERE run_id = ? AND status = 'WORKFLOW_RUNNING'
                  AND rewind_status = 'RUNNING' AND rewind_occurrence_key = ?
                """)) {
            statement.setString(1, sha(rewindTreeHash, "rewindTreeHash"));
            statement.setString(2, runId);
            statement.setString(3, occurrenceKey);
            requireOne(statement, "Rewind completion rejected");
        }
    }

    public void markRewindFailed(String runId, String occurrenceKey, boolean partial,
            String category, String error) throws SQLException {
        failOwnedStage(runId, "rewind_status", Set.of(REWIND_RUNNING),
                "rewind_occurrence_key", occurrenceKey, "REWIND", category, error,
                partial ? REWIND_PARTIAL : REWIND_FAILED);
    }

    private void updateOwnedStage(String runId, String statusColumn, String expected,
            String next, String ownerColumn, String owner, String phase) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE core_demo_runs SET " + statusColumn + " = ?, current_phase = ?, "
                + "updated_at = datetime('now') WHERE run_id = ? AND status = 'WORKFLOW_RUNNING' "
                + "AND " + statusColumn + " = ? AND " + ownerColumn + " = ?")) {
            statement.setString(1, next); statement.setString(2, phase);
            statement.setString(3, runId); statement.setString(4, expected);
            statement.setString(5, owner); requireOne(statement, "Owned stage update rejected");
        }
    }

    private void failOwnedStage(String runId, String statusColumn, Set<String> expected,
            String ownerColumn, String owner, String phase, String category, String error)
            throws SQLException {
        failOwnedStage(runId,statusColumn,expected,ownerColumn,owner,phase,category,error,"FAILED");
    }

    private void failOwnedStage(String runId, String statusColumn, Set<String> expected,
            String ownerColumn, String owner, String phase, String category, String error,
            String terminal) throws SQLException {
        String placeholders = String.join(",", Collections.nCopies(expected.size(), "?"));
        String sql = "UPDATE core_demo_runs SET " + statusColumn + " = ?, current_phase = ?, "
                + "error_category = ?, bounded_error_excerpt = ?, updated_at = datetime('now') "
                + "WHERE run_id = ? AND status = 'WORKFLOW_RUNNING' AND " + statusColumn
                + " IN (" + placeholders + ") AND " + ownerColumn + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i=1; statement.setString(i++,terminal); statement.setString(i++,phase);
            statement.setString(i++,optional(category,128)); statement.setString(i++,bounded(error));
            statement.setString(i++,runId);
            for(String value:expected) statement.setString(i++,value);
            statement.setString(i,owner); requireOne(statement,"Owned stage failure rejected");
        }
    }

    public void startArtifacts(String runId) throws SQLException {
        CoreDemoRunRecord current = findById(runId).orElseThrow(
                () -> new IllegalStateException("Demo run not found: " + runId));
        if (!Set.of(WORKFLOW_SUCCEEDED, WORKFLOW_FAILED, FAILED).contains(current.status())) {
            throw new IllegalStateException("Artifacts cannot start from " + current.status());
        }
        transition(runId, current.status(), ARTIFACTS_WRITING, "REPORT");
        updateArtifactStatus(runId, ARTIFACTS_WRITING, "STAGING");
    }

    public void commitArtifacts(String runId, int exitCode) throws SQLException {
        if (!Set.of(0, 2, 3, 4, 5, 6, 7, 8).contains(exitCode) || exitCode == 1) {
            throw new IllegalArgumentException("Unsupported demo exit code");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE core_demo_runs SET status = CASE WHEN ? = 0 THEN 'COMPLETED' ELSE 'RETAINED_FOR_DIAGNOSTICS' END,
                  artifact_status = 'COMMITTED',
                  exit_code = ?, completed_at = datetime('now'), updated_at = datetime('now')
                WHERE run_id = ? AND status = 'ARTIFACTS_WRITING'
                  AND artifact_status = 'STAGING'
                """)) {
            statement.setInt(1, exitCode);
            statement.setInt(2, exitCode);
            statement.setString(3, runId);
            requireOne(statement, "Artifact commit rejected");
        }
    }

    public void failArtifacts(String runId, String category, String error) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE core_demo_runs SET status = 'RETAINED_FOR_DIAGNOSTICS',
                  artifact_status = 'FAILED',
                  exit_code = 7, error_category = ?, bounded_error_excerpt = ?,
                  completed_at = datetime('now'), updated_at = datetime('now')
                WHERE run_id = ? AND status = 'ARTIFACTS_WRITING'
                """)) {
            statement.setString(1, optional(category, 128));
            statement.setString(2, bounded(error));
            statement.setString(3, runId);
            requireOne(statement, "Artifact failure rejected");
        }
    }

    public void markCleanupPending(String runId) throws SQLException {
        transition(runId, COMPLETED, CLEANUP_PENDING, "CLEANUP");
    }

    public void markCleaned(String runId) throws SQLException {
        CoreDemoRunRecord current = findById(runId).orElseThrow();
        if (!Set.of(COMPLETED, CLEANUP_PENDING).contains(current.status())) {
            throw new IllegalStateException("Cleanup completion rejected");
        }
        transition(runId, current.status(), CLEANED, "CLEANUP");
    }

    public void retainForDiagnostics(String runId, String category, String error)
            throws SQLException {
        CoreDemoRunRecord current = findById(runId).orElseThrow();
        if (!Set.of(PREPARING, FAILED, ARTIFACTS_WRITING, CLEANUP_PENDING)
                .contains(current.status())) {
            throw new IllegalStateException("Diagnostic retention rejected");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE core_demo_runs SET status = 'RETAINED_FOR_DIAGNOSTICS',
                  error_category = COALESCE(?, error_category),
                  bounded_error_excerpt = COALESCE(?, bounded_error_excerpt),
                  updated_at = datetime('now')
                WHERE run_id = ? AND status = ?
                """)) {
            statement.setString(1, optional(category, 128));
            statement.setString(2, bounded(error));
            statement.setString(3, runId);
            statement.setString(4, current.status());
            requireOne(statement, "Diagnostic retention rejected");
        }
    }

    public Optional<CoreDemoRunRecord> findById(String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT + " WHERE run_id = ?")) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(map(rows)) : Optional.empty();
            }
        }
    }

    private void transition(String runId, String from, String to, String phase)
            throws SQLException {
        if (!TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalStateException("Invalid demo transition: " + from + " -> " + to);
        }
        requirePhase(phase);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE core_demo_runs SET status = ?, current_phase = ?,
                  updated_at = datetime('now') WHERE run_id = ? AND status = ?
                """)) {
            statement.setString(1, to);
            statement.setString(2, phase);
            statement.setString(3, runId);
            statement.setString(4, from);
            requireOne(statement, "Demo transition rejected");
        }
    }

    private void updateArtifactStatus(String runId, String status, String artifactStatus)
            throws SQLException {
        if (!ARTIFACTS.contains(artifactStatus)) throw new IllegalArgumentException("Bad artifact status");
        updateFields(runId, status, "artifact_status = ?", artifactStatus);
    }

    private void updateFields(String runId, String status, String assignment, String value)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE core_demo_runs SET " + assignment
                        + ", updated_at = datetime('now') WHERE run_id = ? AND status = ?")) {
            statement.setString(1, value);
            statement.setString(2, runId);
            statement.setString(3, status);
            requireOne(statement, "Demo state update rejected");
        }
    }

    private static void requireOne(PreparedStatement statement, String message)
            throws SQLException {
        if (statement.executeUpdate() != 1) throw new IllegalStateException(message);
    }

    private static String id(String value, String field) {
        String result = text(value, field, 64);
        if (!result.matches("[a-z0-9][a-z0-9-]{7,63}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return result;
    }

    private static String text(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private static String sha(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " is not SHA-256");
        }
        return value;
    }

    private static String optionalSha(String value, String field) {
        return value == null ? null : sha(value, field);
    }

    private static String optional(String value, int max) {
        if (value == null) return null;
        String normalized = value.replace('\r', ' ').replace('\n', ' ');
        return normalized.substring(0, Math.min(max, normalized.length()));
    }

    private String bounded(String value) {
        String safe = sanitizer.sanitize(value);
        if (safe == null) return null;
        safe = ABSOLUTE_PATH.matcher(safe).replaceAll("[ABSOLUTE_PATH_REDACTED]");
        return sanitizer.sanitizeAndTruncate(safe, 4096);
    }

    private static void requirePhase(String phase) {
        if (!PHASES.contains(phase)) throw new IllegalArgumentException("Unknown demo phase: " + phase);
    }

    private CoreDemoRunRecord map(ResultSet rows) throws SQLException {
        return new CoreDemoRunRecord(
                rows.getString("run_id"), rows.getString("scenario_id"),
                rows.getString("fixture_version"), rows.getString("fixture_manifest_hash"),
                rows.getString("seed_tree_hash"), rows.getString("workspace_identity_hash"),
                rows.getInt("headless") == 1, rows.getString("provider_type"),
                rows.getString("status"), rows.getString("current_phase"),
                rows.getString("task_id"), rows.getString("workflow_occurrence_key"),
                rows.getString("baseline_status"), (Integer) rows.getObject("baseline_test_count"),
                rows.getString("baseline_tree_hash"), rows.getString("final_tree_hash"),
                rows.getString("rewind_tree_hash"), rows.getInt("repair_count"),
                rows.getInt("resumed") == 1, rows.getInt("rewound") == 1,
                rows.getString("resume_status"), rows.getString("resume_occurrence_key"),
                rows.getString("rewind_status"), rows.getString("rewind_occurrence_key"),
                rows.getString("artifact_status"), (Integer) rows.getObject("exit_code"),
                rows.getString("error_category"), rows.getString("bounded_error_excerpt"),
                rows.getString("created_at"), rows.getString("updated_at"),
                rows.getString("completed_at"));
    }

    private static final String SELECT = """
            SELECT run_id, scenario_id, fixture_version, fixture_manifest_hash,
              seed_tree_hash, workspace_identity_hash, headless, provider_type,
              status, current_phase, task_id, workflow_occurrence_key,
              baseline_status, baseline_test_count, baseline_tree_hash,
              final_tree_hash, rewind_tree_hash, repair_count, resumed, rewound,
              resume_status, resume_occurrence_key, rewind_status, rewind_occurrence_key,
              artifact_status, exit_code, error_category, bounded_error_excerpt,
              created_at, updated_at, completed_at
            FROM core_demo_runs
            """;

    public record CreateRun(String runId, String scenarioId, String fixtureVersion,
            String fixtureManifestHash, String seedTreeHash, String workspaceIdentityHash,
            String providerType) { }

    public record CoreDemoRunRecord(String runId, String scenarioId, String fixtureVersion,
            String fixtureManifestHash, String seedTreeHash, String workspaceIdentityHash,
            boolean headless, String providerType, String status, String currentPhase,
            String taskId, String workflowOccurrenceKey, String baselineStatus,
            Integer baselineTestCount, String baselineTreeHash, String finalTreeHash,
            String rewindTreeHash, int repairCount, boolean resumed, boolean rewound,
            String resumeStatus, String resumeOccurrenceKey,
            String rewindStatus, String rewindOccurrenceKey,
            String artifactStatus, Integer exitCode, String errorCategory,
            String boundedErrorExcerpt, String createdAt, String updatedAt,
            String completedAt) { }
}
