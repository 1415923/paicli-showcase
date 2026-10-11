package com.paicli.state;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

final class R1aSqliteSupport {
    private R1aSqliteSupport() { }

    static void requireForeignKeys(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA foreign_keys")) {
            if (!result.next() || result.getInt(1) != 1) {
                throw new SQLException("SQLite foreign key enforcement is not enabled");
            }
        }
    }
}
