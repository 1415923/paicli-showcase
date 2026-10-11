package com.paicli.state;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class CodeIndexStore {

    public static final String STATUS_INDEXED = "INDEXED";
    public static final String STATUS_UNCHANGED = "UNCHANGED";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_DELETED = "DELETED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    public static final String CLASS_KIND_CLASS = "CLASS";
    public static final String CLASS_KIND_INTERFACE = "INTERFACE";
    public static final String CLASS_KIND_ENUM = "ENUM";
    public static final String CLASS_KIND_ANNOTATION = "ANNOTATION";
    public static final String CLASS_KIND_RECORD = "RECORD";

    public static final String METHOD_KIND_METHOD = "METHOD";
    public static final String METHOD_KIND_CONSTRUCTOR = "CONSTRUCTOR";

    private final Connection connection;

    public CodeIndexStore(Connection connection) {
        this.connection = connection;
    }

    public void upsertFile(CodeFileRecord file) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO code_files(
                    file_id, relative_path, language, package_name, imports_json,
                    sha256, mtime_millis, size_bytes, status, error_excerpt,
                    unsupported_note, indexed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now'))
                ON CONFLICT(file_id) DO UPDATE SET
                    relative_path = excluded.relative_path,
                    language = excluded.language,
                    package_name = excluded.package_name,
                    imports_json = excluded.imports_json,
                    sha256 = excluded.sha256,
                    mtime_millis = excluded.mtime_millis,
                    size_bytes = excluded.size_bytes,
                    status = excluded.status,
                    error_excerpt = excluded.error_excerpt,
                    unsupported_note = excluded.unsupported_note,
                    indexed_at = datetime('now')
                """)) {
            bindFile(statement, file);
            statement.executeUpdate();
        }
    }

    public void replaceFileIndex(CodeFileRecord file,
                                 List<ClassRecord> classes,
                                 List<MethodRecord> methods,
                                 List<FieldRecord> fields) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            upsertFile(file);
            clearIndexRows(file.fileId());
            insertClasses(classes);
            insertMethods(methods);
            insertFields(fields);
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    public void markFileError(String fileId, String relativePath,
                              String language, String sha256,
                              long mtimeMillis, long sizeBytes,
                              String errorExcerpt) throws SQLException {
        replaceFileIndex(new CodeFileRecord(
                fileId, relativePath, language, null, null, sha256,
                mtimeMillis, sizeBytes, STATUS_ERROR, errorExcerpt,
                null, null), List.of(), List.of(), List.of());
    }

    public void markDeleted(String fileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE code_files
                SET status = ?, indexed_at = datetime('now')
                WHERE file_id = ?
                """)) {
            statement.setString(1, STATUS_DELETED);
            statement.setString(2, fileId);
            statement.executeUpdate();
        }
        clearIndexRows(fileId);
    }

    public void clearIndexRows(String fileId) throws SQLException {
        try (PreparedStatement fields = connection.prepareStatement(
                "DELETE FROM field_index WHERE file_id = ?");
             PreparedStatement methods = connection.prepareStatement(
                     "DELETE FROM method_index WHERE file_id = ?");
             PreparedStatement classes = connection.prepareStatement(
                     "DELETE FROM class_index WHERE file_id = ?")) {
            fields.setString(1, fileId);
            fields.executeUpdate();
            methods.setString(1, fileId);
            methods.executeUpdate();
            classes.setString(1, fileId);
            classes.executeUpdate();
        }
    }

    public Optional<CodeFileRecord> findFile(String relativePath)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_FILE + " WHERE relative_path = ?")) {
            statement.setString(1, relativePath);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(toFile(resultSet)) : Optional.empty();
            }
        }
    }

    public Optional<CodeFileRecord> findFileById(String fileId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_FILE + " WHERE file_id = ?")) {
            statement.setString(1, fileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(toFile(resultSet)) : Optional.empty();
            }
        }
    }

    public List<CodeFileRecord> findFilesByLanguage(String language)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_FILE + " WHERE language = ? ORDER BY relative_path")) {
            statement.setString(1, language);
            List<CodeFileRecord> records = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    records.add(toFile(resultSet));
                }
            }
            return records;
        }
    }

    public List<ClassRecord> findClassesByFile(String fileId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_CLASS + " WHERE file_id = ? ORDER BY start_line, class_name")) {
            statement.setString(1, fileId);
            return findClasses(statement);
        }
    }

    public List<MethodRecord> findMethodsByFile(String fileId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_METHOD + " WHERE file_id = ? ORDER BY start_line, method_name")) {
            statement.setString(1, fileId);
            return findMethods(statement);
        }
    }

    public List<FieldRecord> findFieldsByFile(String fileId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_FIELD + " WHERE file_id = ? ORDER BY start_line, field_name")) {
            statement.setString(1, fileId);
            return findFields(statement);
        }
    }

    public List<ClassQueryRecord> findClassesBySimpleName(String name)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_CLASS_QUERY + """
                        WHERE c.class_name = ? AND cf.status IN ('INDEXED', 'UNCHANGED')
                        ORDER BY cf.relative_path, c.start_line, c.qualified_name, c.class_id
                        """)) {
            statement.setString(1, name);
            return findClassQueries(statement);
        }
    }

    public Optional<ClassQueryRecord> findClassByQualifiedName(String qualifiedName)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_CLASS_QUERY + """
                        WHERE c.qualified_name = ? AND cf.status IN ('INDEXED', 'UNCHANGED')
                        ORDER BY cf.relative_path, c.start_line, c.class_id
                        LIMIT 1
                        """)) {
            statement.setString(1, qualifiedName);
            List<ClassQueryRecord> records = findClassQueries(statement);
            return records.isEmpty() ? Optional.empty() : Optional.of(records.getFirst());
        }
    }

    public List<MethodQueryRecord> findMethodsByName(String methodName)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_METHOD_QUERY + """
                        WHERE m.method_name = ? AND cf.status IN ('INDEXED', 'UNCHANGED')
                        ORDER BY cf.relative_path, c.qualified_name, m.start_line,
                                 m.signature, m.method_id
                        """)) {
            statement.setString(1, methodName);
            return findMethodQueries(statement);
        }
    }

    public List<MethodQueryRecord> findMethodsByClassAndName(
            String classId, String methodName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_METHOD_QUERY + """
                        WHERE m.class_id = ? AND m.method_name = ?
                          AND cf.status IN ('INDEXED', 'UNCHANGED')
                        ORDER BY cf.relative_path, m.start_line, m.signature, m.method_id
                        """)) {
            statement.setString(1, classId);
            statement.setString(2, methodName);
            return findMethodQueries(statement);
        }
    }

    public List<MethodQueryRecord> findMethodsByQualifiedClassAndName(
            String qualifiedClassName, String methodName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_METHOD_QUERY + """
                        WHERE c.qualified_name = ? AND m.method_name = ?
                          AND cf.status IN ('INDEXED', 'UNCHANGED')
                        ORDER BY cf.relative_path, m.start_line, m.signature, m.method_id
                        """)) {
            statement.setString(1, qualifiedClassName);
            statement.setString(2, methodName);
            return findMethodQueries(statement);
        }
    }

    public List<FieldQueryRecord> findFieldsByClass(String classId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_FIELD_QUERY + """
                        WHERE f.class_id = ? AND cf.status IN ('INDEXED', 'UNCHANGED')
                        ORDER BY cf.relative_path, f.start_line, f.field_name, f.field_id
                        """)) {
            statement.setString(1, classId);
            return findFieldQueries(statement);
        }
    }

    public List<FieldQueryRecord> findFieldsByClassAndName(
            String classId, String fieldName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_FIELD_QUERY + """
                        WHERE f.class_id = ? AND f.field_name = ?
                          AND cf.status IN ('INDEXED', 'UNCHANGED')
                        ORDER BY cf.relative_path, f.start_line, f.field_id
                        """)) {
            statement.setString(1, classId);
            statement.setString(2, fieldName);
            return findFieldQueries(statement);
        }
    }

    public int countFilesByStatus(String status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM code_files WHERE status = ?")) {
            statement.setString(1, status);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    private void insertClasses(List<ClassRecord> records) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO class_index(
                    class_id, file_id, package_name, class_name, qualified_name,
                    kind, start_line, end_line, modifiers, extends_name,
                    implements_names
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (ClassRecord record : records) {
                statement.setString(1, record.classId());
                statement.setString(2, record.fileId());
                statement.setString(3, record.packageName());
                statement.setString(4, record.className());
                statement.setString(5, record.qualifiedName());
                statement.setString(6, record.kind());
                setNullableInt(statement, 7, record.startLine());
                setNullableInt(statement, 8, record.endLine());
                statement.setString(9, record.modifiers());
                statement.setString(10, record.extendsName());
                statement.setString(11, record.implementsNames());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertMethods(List<MethodRecord> records) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO method_index(
                    method_id, file_id, class_id, method_name, kind, signature,
                    return_type, parameter_types, start_line, end_line,
                    chunk_text, chunk_sha256, modifiers
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (MethodRecord record : records) {
                statement.setString(1, record.methodId());
                statement.setString(2, record.fileId());
                statement.setString(3, record.classId());
                statement.setString(4, record.methodName());
                statement.setString(5, record.kind());
                statement.setString(6, record.signature());
                statement.setString(7, record.returnType());
                statement.setString(8, record.parameterTypes());
                setNullableInt(statement, 9, record.startLine());
                setNullableInt(statement, 10, record.endLine());
                statement.setString(11, record.chunkText());
                statement.setString(12, record.chunkSha256());
                statement.setString(13, record.modifiers());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertFields(List<FieldRecord> records) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO field_index(
                    field_id, file_id, class_id, field_name, field_type,
                    start_line, end_line, modifiers
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (FieldRecord record : records) {
                statement.setString(1, record.fieldId());
                statement.setString(2, record.fileId());
                statement.setString(3, record.classId());
                statement.setString(4, record.fieldName());
                statement.setString(5, record.fieldType());
                setNullableInt(statement, 6, record.startLine());
                setNullableInt(statement, 7, record.endLine());
                statement.setString(8, record.modifiers());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void bindFile(PreparedStatement statement, CodeFileRecord file)
            throws SQLException {
        statement.setString(1, file.fileId());
        statement.setString(2, file.relativePath());
        statement.setString(3, file.language());
        statement.setString(4, file.packageName());
        statement.setString(5, file.importsJson());
        statement.setString(6, file.sha256());
        statement.setLong(7, file.mtimeMillis());
        statement.setLong(8, file.sizeBytes());
        statement.setString(9, file.status());
        statement.setString(10, file.errorExcerpt());
        statement.setString(11, file.unsupportedNote());
    }

    private void setNullableInt(PreparedStatement statement, int index,
                                Integer value) throws SQLException {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setInt(index, value);
        }
    }

    private List<ClassRecord> findClasses(PreparedStatement statement)
            throws SQLException {
        List<ClassRecord> records = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                records.add(toClass(resultSet));
            }
        }
        return records;
    }

    private List<MethodRecord> findMethods(PreparedStatement statement)
            throws SQLException {
        List<MethodRecord> records = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                records.add(toMethod(resultSet));
            }
        }
        return records;
    }

    private List<FieldRecord> findFields(PreparedStatement statement)
            throws SQLException {
        List<FieldRecord> records = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                records.add(toField(resultSet));
            }
        }
        return records;
    }

    private List<ClassQueryRecord> findClassQueries(PreparedStatement statement)
            throws SQLException {
        List<ClassQueryRecord> records = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                records.add(toClassQuery(resultSet));
            }
        }
        return records;
    }

    private List<MethodQueryRecord> findMethodQueries(PreparedStatement statement)
            throws SQLException {
        List<MethodQueryRecord> records = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                records.add(toMethodQuery(resultSet));
            }
        }
        return records;
    }

    private List<FieldQueryRecord> findFieldQueries(PreparedStatement statement)
            throws SQLException {
        List<FieldQueryRecord> records = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                records.add(toFieldQuery(resultSet));
            }
        }
        return records;
    }

    private CodeFileRecord toFile(ResultSet resultSet) throws SQLException {
        return new CodeFileRecord(
                resultSet.getString("file_id"),
                resultSet.getString("relative_path"),
                resultSet.getString("language"),
                resultSet.getString("package_name"),
                resultSet.getString("imports_json"),
                resultSet.getString("sha256"),
                resultSet.getLong("mtime_millis"),
                resultSet.getLong("size_bytes"),
                resultSet.getString("status"),
                resultSet.getString("error_excerpt"),
                resultSet.getString("unsupported_note"),
                resultSet.getString("indexed_at")
        );
    }

    private ClassRecord toClass(ResultSet resultSet) throws SQLException {
        return new ClassRecord(
                resultSet.getString("class_id"),
                resultSet.getString("file_id"),
                resultSet.getString("package_name"),
                resultSet.getString("class_name"),
                resultSet.getString("qualified_name"),
                resultSet.getString("kind"),
                (Integer) resultSet.getObject("start_line"),
                (Integer) resultSet.getObject("end_line"),
                resultSet.getString("modifiers"),
                resultSet.getString("extends_name"),
                resultSet.getString("implements_names")
        );
    }

    private MethodRecord toMethod(ResultSet resultSet) throws SQLException {
        return new MethodRecord(
                resultSet.getString("method_id"),
                resultSet.getString("file_id"),
                resultSet.getString("class_id"),
                resultSet.getString("method_name"),
                resultSet.getString("kind"),
                resultSet.getString("signature"),
                resultSet.getString("return_type"),
                resultSet.getString("parameter_types"),
                (Integer) resultSet.getObject("start_line"),
                (Integer) resultSet.getObject("end_line"),
                resultSet.getString("chunk_text"),
                resultSet.getString("chunk_sha256"),
                resultSet.getString("modifiers")
        );
    }

    private FieldRecord toField(ResultSet resultSet) throws SQLException {
        return new FieldRecord(
                resultSet.getString("field_id"),
                resultSet.getString("file_id"),
                resultSet.getString("class_id"),
                resultSet.getString("field_name"),
                resultSet.getString("field_type"),
                (Integer) resultSet.getObject("start_line"),
                (Integer) resultSet.getObject("end_line"),
                resultSet.getString("modifiers")
        );
    }

    private ClassQueryRecord toClassQuery(ResultSet resultSet)
            throws SQLException {
        return new ClassQueryRecord(
                resultSet.getString("file_id"),
                resultSet.getString("relative_path"),
                resultSet.getString("file_package_name"),
                resultSet.getString("imports_json"),
                resultSet.getString("class_id"),
                resultSet.getString("package_name"),
                resultSet.getString("class_name"),
                resultSet.getString("qualified_name"),
                resultSet.getString("kind"),
                (Integer) resultSet.getObject("start_line"),
                (Integer) resultSet.getObject("end_line"),
                resultSet.getString("modifiers"),
                resultSet.getString("extends_name"),
                resultSet.getString("implements_names")
        );
    }

    private MethodQueryRecord toMethodQuery(ResultSet resultSet)
            throws SQLException {
        return new MethodQueryRecord(
                resultSet.getString("file_id"),
                resultSet.getString("relative_path"),
                resultSet.getString("file_package_name"),
                resultSet.getString("imports_json"),
                resultSet.getString("class_id"),
                resultSet.getString("class_name"),
                resultSet.getString("qualified_name"),
                resultSet.getString("method_id"),
                resultSet.getString("method_name"),
                resultSet.getString("kind"),
                resultSet.getString("signature"),
                resultSet.getString("return_type"),
                resultSet.getString("parameter_types"),
                (Integer) resultSet.getObject("start_line"),
                (Integer) resultSet.getObject("end_line"),
                resultSet.getString("chunk_text"),
                resultSet.getString("chunk_sha256"),
                resultSet.getString("modifiers")
        );
    }

    private FieldQueryRecord toFieldQuery(ResultSet resultSet)
            throws SQLException {
        return new FieldQueryRecord(
                resultSet.getString("file_id"),
                resultSet.getString("relative_path"),
                resultSet.getString("file_package_name"),
                resultSet.getString("imports_json"),
                resultSet.getString("class_id"),
                resultSet.getString("class_name"),
                resultSet.getString("qualified_name"),
                resultSet.getString("field_id"),
                resultSet.getString("field_name"),
                resultSet.getString("field_type"),
                (Integer) resultSet.getObject("start_line"),
                (Integer) resultSet.getObject("end_line"),
                resultSet.getString("modifiers")
        );
    }

    private static final String SELECT_FILE = """
            SELECT file_id, relative_path, language, package_name, imports_json,
                   sha256, mtime_millis, size_bytes, status, error_excerpt,
                   unsupported_note, indexed_at
            FROM code_files
            """;

    private static final String SELECT_CLASS = """
            SELECT class_id, file_id, package_name, class_name, qualified_name,
                   kind, start_line, end_line, modifiers, extends_name,
                   implements_names
            FROM class_index
            """;

    private static final String SELECT_METHOD = """
            SELECT method_id, file_id, class_id, method_name, kind, signature,
                   return_type, parameter_types, start_line, end_line,
                   chunk_text, chunk_sha256, modifiers
            FROM method_index
            """;

    private static final String SELECT_FIELD = """
            SELECT field_id, file_id, class_id, field_name, field_type,
                   start_line, end_line, modifiers
            FROM field_index
            """;

    private static final String SELECT_CLASS_QUERY = """
            SELECT cf.file_id, cf.relative_path,
                   cf.package_name AS file_package_name, cf.imports_json,
                   c.class_id, c.package_name, c.class_name, c.qualified_name,
                   c.kind, c.start_line, c.end_line, c.modifiers,
                   c.extends_name, c.implements_names
            FROM class_index c
            JOIN code_files cf ON cf.file_id = c.file_id
            """;

    private static final String SELECT_METHOD_QUERY = """
            SELECT cf.file_id, cf.relative_path,
                   cf.package_name AS file_package_name, cf.imports_json,
                   c.class_id, c.class_name, c.qualified_name,
                   m.method_id, m.method_name, m.kind, m.signature,
                   m.return_type, m.parameter_types, m.start_line, m.end_line,
                   m.chunk_text, m.chunk_sha256, m.modifiers
            FROM method_index m
            JOIN class_index c ON c.class_id = m.class_id
            JOIN code_files cf ON cf.file_id = m.file_id
            """;

    private static final String SELECT_FIELD_QUERY = """
            SELECT cf.file_id, cf.relative_path,
                   cf.package_name AS file_package_name, cf.imports_json,
                   c.class_id, c.class_name, c.qualified_name,
                   f.field_id, f.field_name, f.field_type,
                   f.start_line, f.end_line, f.modifiers
            FROM field_index f
            JOIN class_index c ON c.class_id = f.class_id
            JOIN code_files cf ON cf.file_id = f.file_id
            """;

    public record CodeFileRecord(
            String fileId,
            String relativePath,
            String language,
            String packageName,
            String importsJson,
            String sha256,
            long mtimeMillis,
            long sizeBytes,
            String status,
            String errorExcerpt,
            String unsupportedNote,
            String indexedAt
    ) {
    }

    public record ClassRecord(
            String classId,
            String fileId,
            String packageName,
            String className,
            String qualifiedName,
            String kind,
            Integer startLine,
            Integer endLine,
            String modifiers,
            String extendsName,
            String implementsNames
    ) {
    }

    public record MethodRecord(
            String methodId,
            String fileId,
            String classId,
            String methodName,
            String kind,
            String signature,
            String returnType,
            String parameterTypes,
            Integer startLine,
            Integer endLine,
            String chunkText,
            String chunkSha256,
            String modifiers
    ) {
    }

    public record FieldRecord(
            String fieldId,
            String fileId,
            String classId,
            String fieldName,
            String fieldType,
            Integer startLine,
            Integer endLine,
            String modifiers
    ) {
    }

    public record ClassQueryRecord(
            String fileId,
            String relativePath,
            String filePackageName,
            String importsJson,
            String classId,
            String packageName,
            String className,
            String qualifiedName,
            String kind,
            Integer startLine,
            Integer endLine,
            String modifiers,
            String extendsName,
            String implementsNames
    ) {
    }

    public record MethodQueryRecord(
            String fileId,
            String relativePath,
            String filePackageName,
            String importsJson,
            String classId,
            String className,
            String qualifiedName,
            String methodId,
            String methodName,
            String kind,
            String signature,
            String returnType,
            String parameterTypes,
            Integer startLine,
            Integer endLine,
            String chunkText,
            String chunkSha256,
            String modifiers
    ) {
    }

    public record FieldQueryRecord(
            String fileId,
            String relativePath,
            String filePackageName,
            String importsJson,
            String classId,
            String className,
            String qualifiedName,
            String fieldId,
            String fieldName,
            String fieldType,
            Integer startLine,
            Integer endLine,
            String modifiers
    ) {
    }
}
