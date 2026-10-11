package com.paicli.state;

import com.paicli.identity.EntityFamily;
import com.paicli.identity.LegacyId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Read-only conservative planner. Final canonical IDs are generated only by a future apply. */
public final class LegacyBackfillPlanner {
    private final Connection connection;
    private final LegacyAliasStore aliases;

    public LegacyBackfillPlanner(Connection connection) throws SQLException {
        this.connection = Objects.requireNonNull(connection);
        R1aSqliteSupport.requireForeignKeys(connection);
        this.aliases = new LegacyAliasStore(connection);
    }

    public DryRunPlan dryRun() throws SQLException {
        List<DryRunItem> items = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT t.task_id,t.goal,t.status,
                            EXISTS(SELECT 1 FROM checkpoints c WHERE c.task_id=t.task_id),
                            (SELECT count(*) FROM coding_runs r WHERE r.task_id=t.task_id)
                     FROM tasks t
                     ORDER BY t.task_id
                     """)) {
            while (rows.next()) {
                String legacyValue = rows.getString(1);
                boolean checkpoint = rows.getInt(4) == 1;
                int runCount = rows.getInt(5);
                if (legacyValue == null || legacyValue.isBlank()
                        || rows.getString(2) == null || rows.getString(3) == null) {
                    items.add(item(legacyValue, Disposition.INVALID_LEGACY_RECORD,
                            checkpoint, runCount, "Required v1.1 Task fields are missing"));
                    continue;
                }
                LegacyAliasStore.Resolution resolution =
                        aliases.resolve(new LegacyId(EntityFamily.TASK, legacyValue));
                if (resolution instanceof LegacyAliasStore.Resolution.Ambiguous) {
                    items.add(item(legacyValue, Disposition.AMBIGUOUS_LEGACY_ID,
                            checkpoint, runCount, "Typed legacy alias resolves ambiguously"));
                } else if (resolution instanceof LegacyAliasStore.Resolution.Invalid) {
                    items.add(item(legacyValue, Disposition.INVALID_LEGACY_RECORD,
                            checkpoint, runCount, "Typed legacy alias is invalid"));
                } else if (resolution instanceof LegacyAliasStore.Resolution.Mapped) {
                    items.add(item(legacyValue, Disposition.ALREADY_MAPPED,
                            checkpoint, runCount, "A canonical Task mapping already exists"));
                } else if (!checkpoint) {
                    items.add(item(legacyValue, Disposition.CONTINUATION_UNAVAILABLE,
                            false, runCount, "No trustworthy checkpoint exists"));
                } else {
                    items.add(item(legacyValue, Disposition.ELIGIBLE, true, runCount,
                            "Checkpoint exists for future bounded compatibility review"));
                }
            }
        }
        return new DryRunPlan(List.copyOf(items));
    }

    private static DryRunItem item(String legacyValue, Disposition disposition,
                                   boolean checkpoint, int runCount, String reason) {
        return new DryRunItem(legacyValue, disposition, ThreadClassification.LEGACY_UNCERTAIN,
                checkpoint ? ContinuationAssessment.CHECKPOINT_PRESENT_REVIEW_REQUIRED
                        : ContinuationAssessment.CONTINUATION_UNAVAILABLE,
                "tasks:" + safeLocator(legacyValue), reason, runCount, false, false);
    }

    private static String safeLocator(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public enum Disposition {
        ELIGIBLE, LEGACY_UNCERTAIN, CONTINUATION_UNAVAILABLE,
        AMBIGUOUS_LEGACY_ID, INVALID_LEGACY_RECORD, ALREADY_MAPPED
    }

    public enum ThreadClassification { LEGACY_UNCERTAIN }

    public enum ContinuationAssessment {
        CHECKPOINT_PRESENT_REVIEW_REQUIRED, CONTINUATION_UNAVAILABLE
    }

    public record DryRunItem(
            String legacyTaskId,
            Disposition disposition,
            ThreadClassification threadClassification,
            ContinuationAssessment continuationAssessment,
            String provenance,
            String reason,
            int existingRunCount,
            boolean inferredParentChild,
            boolean createsGeneration) { }

    public record DryRunPlan(List<DryRunItem> items) {
        public DryRunPlan { items = List.copyOf(items); }
    }
}
