package com.paicli.state;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Persists deterministic skill selections; callers must sanitize context excerpts. */
public class SkillSelectionStore {

    public static final String STAGE_PLAN = "PLAN";
    public static final String STAGE_EXECUTE = "EXECUTE";
    public static final String STAGE_REPAIR = "REPAIR";

    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_ERROR = "ERROR";

    private final Connection connection;

    public SkillSelectionStore(Connection connection) {
        this.connection = connection;
    }

    public void recordCompleted(String selectionId, String runId,
                                String taskId, String stage,
                                String occurrenceKey, String inputSha256,
                                List<SelectedSkillInput> selectedSkills)
            throws SQLException {
        Objects.requireNonNull(selectedSkills, "selectedSkills");
        inTransaction(() -> {
            insertSelection(selectionId, runId, taskId, stage, occurrenceKey,
                    inputSha256, STATUS_COMPLETED, selectedSkills.size(), null);
            for (SelectedSkillInput selected : selectedSkills) {
                insertSelected(selectionId, runId, taskId, selected);
            }
        });
    }

    public void recordError(String selectionId, String runId,
                            String taskId, String stage,
                            String occurrenceKey, String inputSha256,
                            String errorExcerpt) throws SQLException {
        insertSelection(selectionId, runId, taskId, stage, occurrenceKey,
                inputSha256, STATUS_ERROR, 0, errorExcerpt);
    }

    public Optional<SkillSelectionRecord> findSelectionById(String selectionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECTION_COLUMNS + " WHERE selection_id = ?")) {
            statement.setString(1, selectionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(toSelectionRecord(resultSet))
                        : Optional.empty();
            }
        }
    }

    public Optional<SkillSelectionRecord> findByOccurrence(
            String runId, String stage, String occurrenceKey)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECTION_COLUMNS
                        + " WHERE run_id = ? AND stage = ? AND occurrence_key = ?")) {
            statement.setString(1, runId);
            statement.setString(2, stage);
            statement.setString(3, occurrenceKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(toSelectionRecord(resultSet))
                        : Optional.empty();
            }
        }
    }

    public List<SkillSelectionRecord> findSelectionsByRun(String runId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECTION_COLUMNS + """
                        WHERE run_id = ?
                        ORDER BY created_at, rowid
                        """)) {
            statement.setString(1, runId);
            List<SkillSelectionRecord> records = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    records.add(toSelectionRecord(resultSet));
                }
            }
            return records;
        }
    }

    public List<SelectedSkillRecord> findSelectedBySelection(String selectionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECTED_COLUMNS + """
                        WHERE selection_id = ?
                        ORDER BY rank, selected_skill_id
                        """)) {
            statement.setString(1, selectionId);
            return findSelected(statement);
        }
    }

    public List<SelectedSkillRecord> findSelectedByRun(String runId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECTED_COLUMNS + """
                        WHERE run_id = ?
                        ORDER BY created_at, rowid
                        """)) {
            statement.setString(1, runId);
            return findSelected(statement);
        }
    }

    private void insertSelection(String selectionId, String runId,
                                 String taskId, String stage,
                                 String occurrenceKey, String inputSha256,
                                 String status, int selectedCount,
                                 String errorExcerpt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO skill_selection_runs(
                    selection_id, run_id, task_id, stage, occurrence_key,
                    input_sha256, status, selected_count, error_excerpt
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, selectionId);
            statement.setString(2, runId);
            statement.setString(3, taskId);
            statement.setString(4, stage);
            statement.setString(5, occurrenceKey);
            statement.setString(6, inputSha256);
            statement.setString(7, status);
            statement.setInt(8, selectedCount);
            statement.setString(9, errorExcerpt);
            statement.executeUpdate();
        }
    }

    private void insertSelected(String selectionId, String runId,
                                String taskId, SelectedSkillInput selected)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO selected_skills(
                    selected_skill_id, selection_id, skill_load_id,
                    run_id, task_id, skill_name, rank, score,
                    reason, context_excerpt
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, selected.selectedSkillId());
            statement.setString(2, selectionId);
            statement.setString(3, selected.skillLoadId());
            statement.setString(4, runId);
            statement.setString(5, taskId);
            statement.setString(6, selected.skillName());
            statement.setInt(7, selected.rank());
            statement.setDouble(8, selected.score());
            statement.setString(9, selected.reason());
            statement.setString(10, selected.contextExcerpt());
            statement.executeUpdate();
        }
    }

    private List<SelectedSkillRecord> findSelected(PreparedStatement statement)
            throws SQLException {
        List<SelectedSkillRecord> records = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                records.add(toSelectedRecord(resultSet));
            }
        }
        return records;
    }

    private SkillSelectionRecord toSelectionRecord(ResultSet resultSet)
            throws SQLException {
        return new SkillSelectionRecord(
                resultSet.getString("selection_id"),
                resultSet.getString("run_id"),
                resultSet.getString("task_id"),
                resultSet.getString("stage"),
                resultSet.getString("occurrence_key"),
                resultSet.getString("input_sha256"),
                resultSet.getString("status"),
                resultSet.getInt("selected_count"),
                resultSet.getString("error_excerpt"),
                resultSet.getString("created_at")
        );
    }

    private SelectedSkillRecord toSelectedRecord(ResultSet resultSet)
            throws SQLException {
        return new SelectedSkillRecord(
                resultSet.getString("selected_skill_id"),
                resultSet.getString("selection_id"),
                resultSet.getString("skill_load_id"),
                resultSet.getString("run_id"),
                resultSet.getString("task_id"),
                resultSet.getString("skill_name"),
                resultSet.getInt("rank"),
                resultSet.getDouble("score"),
                resultSet.getString("reason"),
                resultSet.getString("context_excerpt"),
                resultSet.getString("created_at")
        );
    }

    private void inTransaction(SqlOperation operation) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        if (previousAutoCommit) {
            connection.setAutoCommit(false);
        }
        try {
            operation.run();
            if (previousAutoCommit) {
                connection.commit();
            }
        } catch (SQLException | RuntimeException e) {
            if (previousAutoCommit) {
                connection.rollback();
            }
            throw e;
        } finally {
            if (previousAutoCommit) {
                connection.setAutoCommit(true);
            }
        }
    }

    private static final String SELECTION_COLUMNS = """
            SELECT selection_id, run_id, task_id, stage, occurrence_key,
                   input_sha256, status, selected_count, error_excerpt, created_at
            FROM skill_selection_runs
            """;

    private static final String SELECTED_COLUMNS = """
            SELECT selected_skill_id, selection_id, skill_load_id, run_id,
                   task_id, skill_name, rank, score, reason, context_excerpt,
                   created_at
            FROM selected_skills
            """;

    public record SelectedSkillInput(
            String selectedSkillId,
            String skillLoadId,
            String skillName,
            int rank,
            double score,
            String reason,
            String contextExcerpt
    ) {
        public SelectedSkillInput {
            Objects.requireNonNull(selectedSkillId, "selectedSkillId");
            Objects.requireNonNull(skillLoadId, "skillLoadId");
            Objects.requireNonNull(skillName, "skillName");
            if (rank < 1) {
                throw new IllegalArgumentException("rank must be positive");
            }
            if (!Double.isFinite(score) || score < 0) {
                throw new IllegalArgumentException(
                        "score must be finite and non-negative");
            }
        }
    }

    public record SkillSelectionRecord(
            String selectionId,
            String runId,
            String taskId,
            String stage,
            String occurrenceKey,
            String inputSha256,
            String status,
            int selectedCount,
            String errorExcerpt,
            String createdAt
    ) {
    }

    public record SelectedSkillRecord(
            String selectedSkillId,
            String selectionId,
            String skillLoadId,
            String runId,
            String taskId,
            String skillName,
            int rank,
            double score,
            String reason,
            String contextExcerpt,
            String createdAt
    ) {
    }

    @FunctionalInterface
    private interface SqlOperation {
        void run() throws SQLException;
    }
}
