package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.identity.LegacyId;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Typed legacy alias persistence. It is not wired to user Resume or canonical lookup. */
public final class LegacyAliasStore {
    private final Connection connection;

    public LegacyAliasStore(Connection connection) throws SQLException {
        this.connection = Objects.requireNonNull(connection);
        R1aSqliteSupport.requireForeignKeys(connection);
    }

    public AddResult add(LegacyAlias alias) throws SQLException {
        Objects.requireNonNull(alias);
        if (!connection.getAutoCommit()) {
            throw new SQLException("Nested legacy alias transactions are not supported");
        }
        connection.setAutoCommit(false);
        try {
            Resolution current = resolve(alias.legacyId());
            if (current instanceof Resolution.Ambiguous) {
                connection.rollback();
                return AddResult.AMBIGUOUS_LEGACY_ID;
            }
            if (current instanceof Resolution.Mapped mapped
                    && alias.canonicalTarget() != null
                    && !mapped.target().equals(alias.canonicalTarget())) {
                connection.rollback();
                return AddResult.AMBIGUOUS_LEGACY_ID;
            }
            Optional<LegacyAlias> exact = findExact(alias.legacyId(), alias.sourceType(),
                    alias.sourceReference());
            if (exact.isPresent()) {
                connection.rollback();
                return exact.get().equals(alias) ? AddResult.ALREADY_EXISTS
                        : AddResult.INTEGRITY_VIOLATION;
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO legacy_aliases(
                      legacy_entity_family,legacy_value,canonical_target_id,classification,
                      mapping_status,source_type,source_reference,provenance,
                      continuation_status,migration_version,created_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                statement.setString(1, alias.legacyId().declaredFamily().name());
                statement.setString(2, alias.legacyId().value());
                if (alias.canonicalTarget() == null) statement.setNull(3, java.sql.Types.VARCHAR);
                else statement.setString(3, alias.canonicalTarget().value());
                statement.setString(4, alias.classification().name());
                statement.setString(5, alias.mappingStatus().name());
                statement.setString(6, alias.sourceType());
                statement.setString(7, alias.sourceReference());
                statement.setString(8, alias.provenance());
                statement.setString(9, alias.continuationStatus().name());
                statement.setInt(10, alias.migrationVersion());
                statement.setString(11, alias.createdAt().toString());
                statement.executeUpdate();
            }
            connection.commit();
            return AddResult.SUCCESS;
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public Resolution resolve(LegacyId legacyId) throws SQLException {
        Objects.requireNonNull(legacyId);
        List<LegacyAlias> aliases = findAll(legacyId);
        if (aliases.isEmpty()) return new Resolution.NotFound();
        if (aliases.stream().anyMatch(alias -> alias.mappingStatus() == LegacyAlias.MappingStatus.INVALID)) {
            return new Resolution.Invalid("INVALID_LEGACY_RECORD");
        }
        List<CanonicalId> targets = aliases.stream().map(LegacyAlias::canonicalTarget)
                .filter(Objects::nonNull).distinct().toList();
        if (targets.size() > 1 || aliases.stream().anyMatch(
                alias -> alias.mappingStatus() == LegacyAlias.MappingStatus.AMBIGUOUS)) {
            return new Resolution.Ambiguous(targets.size());
        }
        return targets.size() == 1 ? new Resolution.Mapped(targets.getFirst())
                : new Resolution.Unmapped(List.copyOf(aliases));
    }

    public List<LegacyAlias> findAll(LegacyId legacyId) throws SQLException {
        List<LegacyAlias> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT canonical_target_id,classification,mapping_status,source_type,
                       source_reference,provenance,continuation_status,migration_version,created_at
                FROM legacy_aliases
                WHERE legacy_entity_family=? AND legacy_value=?
                ORDER BY source_type,source_reference
                """)) {
            statement.setString(1, legacyId.declaredFamily().name());
            statement.setString(2, legacyId.value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(read(legacyId, rows));
            }
        }
        return List.copyOf(result);
    }

    private Optional<LegacyAlias> findExact(LegacyId legacyId, String sourceType,
                                             String sourceReference) throws SQLException {
        return findAll(legacyId).stream().filter(alias -> alias.sourceType().equals(sourceType)
                && alias.sourceReference().equals(sourceReference)).findFirst();
    }

    private static LegacyAlias read(LegacyId legacyId, ResultSet row) throws SQLException {
        String target = row.getString(1);
        return new LegacyAlias(legacyId, target == null ? null : CanonicalId.parse(target),
                LegacyAlias.Classification.valueOf(row.getString(2)),
                LegacyAlias.MappingStatus.valueOf(row.getString(3)), row.getString(4),
                row.getString(5), row.getString(6),
                LegacyAlias.ContinuationStatus.valueOf(row.getString(7)), row.getInt(8),
                Instant.parse(row.getString(9)));
    }

    public enum AddResult {
        SUCCESS, ALREADY_EXISTS, AMBIGUOUS_LEGACY_ID, INVALID_STATE, INTEGRITY_VIOLATION
    }

    public sealed interface Resolution permits Resolution.NotFound, Resolution.Unmapped,
            Resolution.Mapped, Resolution.Ambiguous, Resolution.Invalid {
        record NotFound() implements Resolution { }
        record Unmapped(List<LegacyAlias> aliases) implements Resolution { }
        record Mapped(CanonicalId target) implements Resolution { }
        record Ambiguous(int distinctTargetCount) implements Resolution { }
        record Invalid(String reason) implements Resolution { }
    }
}
