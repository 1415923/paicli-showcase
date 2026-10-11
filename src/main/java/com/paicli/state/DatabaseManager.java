package com.paicli.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.ResultSet;

public class DatabaseManager implements AutoCloseable {

    private final Connection connection;

    public DatabaseManager(Path stateDir) throws IOException, SQLException {
        Files.createDirectories(stateDir);
        Path databaseFile = stateDir.resolve("paicli.db");
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        initializePragmas();
    }

    public Connection getConnection() {
        return connection;
    }

    private void initializePragmas() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA journal_mode = WAL");
            try (ResultSet result = statement.executeQuery("PRAGMA foreign_keys")) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new SQLException("SQLite foreign key enforcement is not enabled");
                }
            }
        }
    }

    @Override
    public void close() throws SQLException {
        if (!connection.isClosed()) {
            connection.close();
        }
    }
}
