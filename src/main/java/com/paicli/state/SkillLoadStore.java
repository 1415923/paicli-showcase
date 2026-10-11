package com.paicli.state;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Persists skill discovery outcomes; callers must sanitize bounded text fields. */
public class SkillLoadStore {

    public static final String STATUS_LOADED = "LOADED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_ERROR = "ERROR";

    private final Connection connection;

    public Optional<String> packageHash(String loadId) throws SQLException {
        try (var query = connection.prepareStatement("SELECT package_sha256 FROM skill_package_bindings WHERE skill_load_id=?")) {
            query.setString(1,loadId);
            try(var rows=query.executeQuery()) { return rows.next()?Optional.of(rows.getString(1)):Optional.empty(); }
        }
    }

    public void bindPackage(String loadId,String hash,String manifest) throws SQLException {
        try(var insert=connection.prepareStatement("INSERT INTO skill_package_bindings VALUES(?,?,?)")) {
            insert.setString(1,loadId);insert.setString(2,hash);insert.setString(3,manifest);insert.executeUpdate();
        }
    }

    public void recordAccess(Access fact) throws SQLException {
        try(var insert=connection.prepareStatement("""
                INSERT INTO skill_accesses(access_id,skill_load_id,kind,tool_call_id,occurrence_id,
                    resource_path,content_sha256,offset_chars,returned_chars,status) VALUES(?,?,?,?,?,?,?,?,?,?)
                """)) {
            insert.setString(1,fact.id());insert.setString(2,fact.loadId());insert.setString(3,fact.kind());
            insert.setString(4,fact.toolCallId());insert.setString(5,fact.occurrenceId());
            insert.setString(6,fact.resourcePath());insert.setString(7,fact.hash());
            insert.setInt(8,fact.offset());insert.setInt(9,fact.chars());insert.setString(10,fact.status());
            insert.executeUpdate();
        }
    }

    public List<Access> accessesForRun(String runId) throws SQLException {
        try(var query=connection.prepareStatement("""
                SELECT a.* FROM skill_accesses a JOIN skill_loads l USING(skill_load_id)
                WHERE l.run_id=? ORDER BY a.rowid LIMIT 201
                """)) {
            query.setString(1,runId);var result=new ArrayList<Access>();
            try(var rows=query.executeQuery()) { while(rows.next()) result.add(new Access(
                    rows.getString("access_id"),rows.getString("skill_load_id"),rows.getString("kind"),
                    rows.getString("tool_call_id"),rows.getString("occurrence_id"),rows.getString("resource_path"),
                    rows.getString("content_sha256"),rows.getInt("offset_chars"),rows.getInt("returned_chars"),rows.getString("status"))); }
            return List.copyOf(result);
        }
    }

    public List<String> reportForTaskRun(String task,String run) throws SQLException {
        var lines=new ArrayList<String>();
        try(var query=connection.prepareStatement("""
                SELECT l.name,a.kind,a.resource_path,a.status,a.tool_call_id,a.occurrence_id,
                       a.offset_chars,a.returned_chars FROM skill_accesses a JOIN skill_loads l USING(skill_load_id)
                WHERE l.task_id=? AND l.run_id=? ORDER BY a.rowid LIMIT 21
                """)) {
            query.setString(1,task);query.setString(2,run);
            try(var rows=query.executeQuery()) { while(rows.next()) {
                if(lines.size()==20){lines.add("Skills: more access facts retained in audit.");break;}
                lines.add("Skill "+rows.getString(1)+" "+rows.getString(2)+" "+rows.getString(3)
                        +" status="+rows.getString(4)+" call="+rows.getString(5)+" occurrence="+rows.getString(6)
                        +" offset="+rows.getInt(7)+" chars="+rows.getInt(8));
            }}
        }
        return lines;
    }

    public record Access(String id,String loadId,String kind,String toolCallId,String occurrenceId,
                         String resourcePath,String hash,int offset,int chars,String status) { }

    public SkillLoadStore(Connection connection) {
        this.connection = connection;
    }

    public void recordLoaded(String skillLoadId, String skillId,
                             String runId, String taskId, String name,
                             String path, String description,
                             String contentSha256) throws SQLException {
        insert(skillLoadId, skillId, runId, taskId, name, path,
                description, contentSha256, STATUS_LOADED, null);
    }

    public void recordRejected(String skillLoadId, String skillId,
                               String runId, String taskId, String name,
                               String path, String errorExcerpt)
            throws SQLException {
        insert(skillLoadId, skillId, runId, taskId, name, path,
                null, null, STATUS_REJECTED, errorExcerpt);
    }

    public void recordError(String skillLoadId, String skillId,
                            String runId, String taskId, String name,
                            String path, String errorExcerpt)
            throws SQLException {
        insert(skillLoadId, skillId, runId, taskId, name, path,
                null, null, STATUS_ERROR, errorExcerpt);
    }

    public Optional<SkillLoadRecord> findById(String skillLoadId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + " WHERE skill_load_id = ?")) {
            statement.setString(1, skillLoadId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(toRecord(resultSet)) : Optional.empty();
            }
        }
    }

    public Optional<SkillLoadRecord> findByRunAndPath(String runId, String path)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + " WHERE run_id = ? AND path = ?")) {
            statement.setString(1, runId);
            statement.setString(2, path);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(toRecord(resultSet)) : Optional.empty();
            }
        }
    }

    public List<SkillLoadRecord> findByRun(String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + """
                        WHERE run_id = ?
                        ORDER BY loaded_at, rowid
                        """)) {
            statement.setString(1, runId);
            return findMany(statement);
        }
    }

    public List<SkillLoadRecord> findByStatus(String status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + """
                        WHERE status = ?
                        ORDER BY loaded_at, rowid
                        """)) {
            statement.setString(1, status);
            return findMany(statement);
        }
    }

    private void insert(String skillLoadId, String skillId,
                        String runId, String taskId, String name,
                        String path, String description, String contentSha256,
                        String status, String errorExcerpt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO skill_loads(
                    skill_load_id, skill_id, run_id, task_id, name, path,
                    description, content_sha256, status, error_excerpt
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, skillLoadId);
            statement.setString(2, skillId);
            statement.setString(3, runId);
            statement.setString(4, taskId);
            statement.setString(5, name);
            statement.setString(6, path);
            statement.setString(7, description);
            statement.setString(8, contentSha256);
            statement.setString(9, status);
            statement.setString(10, errorExcerpt);
            statement.executeUpdate();
        }
    }

    private List<SkillLoadRecord> findMany(PreparedStatement statement)
            throws SQLException {
        List<SkillLoadRecord> records = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                records.add(toRecord(resultSet));
            }
        }
        return records;
    }

    private SkillLoadRecord toRecord(ResultSet resultSet) throws SQLException {
        return new SkillLoadRecord(
                resultSet.getString("skill_load_id"),
                resultSet.getString("skill_id"),
                resultSet.getString("run_id"),
                resultSet.getString("task_id"),
                resultSet.getString("name"),
                resultSet.getString("path"),
                resultSet.getString("description"),
                resultSet.getString("content_sha256"),
                resultSet.getString("status"),
                resultSet.getString("error_excerpt"),
                resultSet.getString("loaded_at")
        );
    }

    private static final String SELECT_COLUMNS = """
            SELECT skill_load_id, skill_id, run_id, task_id, name, path,
                   description, content_sha256, status, error_excerpt, loaded_at
            FROM skill_loads
            """;

    public record SkillLoadRecord(
            String skillLoadId,
            String skillId,
            String runId,
            String taskId,
            String name,
            String path,
            String description,
            String contentSha256,
            String status,
            String errorExcerpt,
            String loadedAt
    ) {
    }
}
