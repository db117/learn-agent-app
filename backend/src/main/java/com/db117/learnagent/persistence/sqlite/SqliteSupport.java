package com.db117.learnagent.persistence.sqlite;

import java.sql.*;
import java.time.Instant;

/** SQLite 适配器共用的边界工具；不把数据库细节泄漏到 Domain。 */
final class SqliteSupport {
    private SqliteSupport() {
    }

    /** SQLite 的外键开关按连接生效，所以每次从连接池取出连接都显式打开。 */
    static void enableForeignKeys(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
    }

    /** 优先读取 JDBC 生成键，驱动未返回时再用 SQLite 当前连接的 rowid 兜底。 */
    static long generatedId(Connection connection, PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (keys.next()) {
                return keys.getLong(1);
            }
        }
        try (Statement fallback = connection.createStatement();
             ResultSet result = fallback.executeQuery("SELECT last_insert_rowid()")) {
            result.next();
            return result.getLong(1);
        }
    }

    static String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    /** NULL 时间保持 NULL；数据库字段的可选性由 Domain 对应的状态规则决定。 */
    static Instant parseInstant(ResultSet result, String column) throws SQLException {
        String value = result.getString(column);
        return value == null ? null : Instant.parse(value);
    }

    static int bool(boolean value) {
        return value ? 1 : 0;
    }

    static boolean bool(ResultSet result, String column) throws SQLException {
        return result.getInt(column) != 0;
    }

    static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    static void nullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setLong(index, value);
        }
    }

    /** 更新聚合时必须恰好命中一行，避免静默丢失状态变化。 */
    static void requireUpdated(int count, String entity, long id) {
        if (count != 1) {
            throw new IllegalStateException("Cannot update missing " + entity + ": " + id);
        }
    }
}
