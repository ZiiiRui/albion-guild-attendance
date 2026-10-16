package com.albion.attendance.storage;

import com.albion.attendance.core.DailyReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Stores the original Python schema and JSON contract, including existing daily history. */
public class AttendanceRepository {
    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS attendance_runs (
                server VARCHAR(16) NOT NULL,
                guild_id VARCHAR(128) NOT NULL,
                run_date DATE NOT NULL,
                PRIMARY KEY (server, guild_id, run_date)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin
            """,
            """
            CREATE TABLE IF NOT EXISTS attendance_reports (
                server VARCHAR(16) NOT NULL,
                guild_id VARCHAR(128) NOT NULL,
                report_date DATE NOT NULL,
                cutoff_utc VARCHAR(40) NOT NULL,
                report_json LONGTEXT NOT NULL,
                PRIMARY KEY (server, guild_id, report_date)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin
            """,
            """
            CREATE TABLE IF NOT EXISTS attendance_members (
                server VARCHAR(16) NOT NULL,
                guild_id VARCHAR(128) NOT NULL,
                report_date DATE NOT NULL,
                player_id VARCHAR(128) NOT NULL,
                player_name VARCHAR(255) NOT NULL,
                attended BOOLEAN NOT NULL,
                in_roster BOOLEAN NOT NULL,
                battle_ids_json LONGTEXT NOT NULL,
                PRIMARY KEY (server, guild_id, report_date, player_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin
            """);

    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public AttendanceRepository(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

    public void initialize() {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            for (String sql : SCHEMA) {
                statement.execute(sql);
            }
        } catch (SQLException exception) {
            throw new StorageException("Cannot initialize attendance tables", exception);
        }
    }

    @FunctionalInterface
    public interface GuildOperation<T> {
        T execute(Connection connection) throws Exception;
    }

    /** The same physical connection owns the MySQL lock throughout the operation. */
    public <T> T withGuildLock(String server, String guildId, GuildOperation<T> operation) throws Exception {
        String key = "attendance:" + sha256(server + ":" + guildId).substring(0, 48);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            if (!lockResult(connection, "SELECT GET_LOCK(?, 0)", key)) {
                throw new IllegalStateException("Another collector is running for this guild");
            }
            Throwable primary = null;
            try {
                return operation.execute(connection);
            } catch (Exception | Error failure) {
                primary = failure;
                throw failure;
            } finally {
                SQLException cleanupFailure = null;
                try {
                    connection.rollback();
                } catch (SQLException failure) {
                    cleanupFailure = failure;
                }
                try {
                    if (!lockResult(connection, "SELECT RELEASE_LOCK(?)", key)) {
                        throw new SQLException("The guild lock was not released by its owning connection");
                    }
                } catch (SQLException failure) {
                    if (cleanupFailure == null) {
                        cleanupFailure = failure;
                    } else {
                        cleanupFailure.addSuppressed(failure);
                    }
                }
                if (cleanupFailure != null) {
                    try {
                        connection.abort(Runnable::run);
                    } catch (SQLException abortFailure) {
                        cleanupFailure.addSuppressed(abortFailure);
                    }
                    if (primary != null) {
                        primary.addSuppressed(cleanupFailure);
                    } else {
                        throw cleanupFailure;
                    }
                }
            }
        }
    }

    private static boolean lockResult(Connection connection, String sql, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() && results.getInt(1) == 1 && !results.wasNull();
            }
        }
    }

    /** A report and all member rows are replaced in one transaction. */
    public void saveReport(Connection connection, DailyReport report) {
        JsonNode payload = mapper.valueToTree(report);
        String server = payload.path("server").asText();
        String guildId = payload.path("guild_id").asText();
        LocalDate date = LocalDate.parse(payload.path("date").asText());
        String cutoff = payload.path("cutoff_utc").asText();
        inTransaction(connection, () -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT cutoff_utc FROM attendance_reports "
                    + "WHERE server=? AND guild_id=? AND report_date=? FOR UPDATE")) {
                bindDay(statement, server, guildId, date);
                try (ResultSet previous = statement.executeQuery()) {
                    if (previous.next() && Instant.parse(previous.getString(1)).isAfter(Instant.parse(cutoff))) {
                        throw new IllegalArgumentException("Refusing to replace a report with an earlier cutoff");
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO attendance_reports "
                    + "(server,guild_id,report_date,cutoff_utc,report_json) VALUES (?,?,?,?,?) "
                    + "ON DUPLICATE KEY UPDATE cutoff_utc=VALUES(cutoff_utc),report_json=VALUES(report_json)")) {
                bindDay(statement, server, guildId, date);
                statement.setString(4, cutoff);
                statement.setString(5, mapper.writeValueAsString(report));
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM attendance_members "
                    + "WHERE server=? AND guild_id=? AND report_date=?")) {
                bindDay(statement, server, guildId, date);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO attendance_members "
                    + "(server,guild_id,report_date,player_id,player_name,attended,in_roster,battle_ids_json) "
                    + "VALUES (?,?,?,?,?,?,?,?)")) {
                for (JsonNode member : payload.path("members")) {
                    bindDay(statement, server, guildId, date);
                    statement.setString(4, member.path("player_id").asText());
                    statement.setString(5, member.path("name").asText());
                    statement.setBoolean(6, member.path("attended").asBoolean());
                    statement.setBoolean(7, member.path("in_roster").asBoolean());
                    statement.setString(8, mapper.writeValueAsString(member.path("battle_ids")));
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        });
    }

    public boolean hasRun(Connection connection, String server, String guildId, LocalDate date) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM attendance_runs "
                + "WHERE server=? AND guild_id=? AND run_date=?")) {
            bindDay(statement, server, guildId, date);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        } catch (SQLException exception) {
            throw new StorageException("Cannot query daily run", exception);
        }
    }

    public void markRun(Connection connection, String server, String guildId, LocalDate date) {
        inTransaction(connection, () -> {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO attendance_runs "
                    + "(server,guild_id,run_date) VALUES (?,?,?) ON DUPLICATE KEY UPDATE run_date=VALUES(run_date)")) {
                bindDay(statement, server, guildId, date);
                statement.executeUpdate();
            }
        });
    }

    public Optional<DailyReport> findReport(String server, String guildId, LocalDate date) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT report_json FROM attendance_reports "
                     + "WHERE server=? AND guild_id=? AND report_date=?")) {
            bindDay(statement, server, guildId, date);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(mapper.readValue(results.getString(1), DailyReport.class)) : Optional.empty();
            }
        } catch (SQLException | IOException exception) {
            throw new StorageException("Cannot read attendance report", exception);
        }
    }

    public List<LocalDate> listDates(String server, String guildId, int limit) {
        if (limit < 1 || limit > 3660) {
            throw new IllegalArgumentException("Report limit must be between 1 and 3660");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT report_date FROM attendance_reports "
                     + "WHERE server=? AND guild_id=? ORDER BY report_date DESC LIMIT ?")) {
            statement.setString(1, server);
            statement.setString(2, guildId);
            statement.setInt(3, limit);
            try (ResultSet results = statement.executeQuery()) {
                List<LocalDate> dates = new ArrayList<>();
                while (results.next()) {
                    dates.add(results.getDate(1).toLocalDate());
                }
                return List.copyOf(dates);
            }
        } catch (SQLException exception) {
            throw new StorageException("Cannot list attendance reports", exception);
        }
    }

    public List<String> findGuildIdsByName(String server, String guildName) {
        String sql = "SELECT reports.guild_id,reports.report_json FROM attendance_reports reports "
                + "JOIN (SELECT guild_id,MAX(report_date) AS latest_date FROM attendance_reports "
                + "WHERE server=? GROUP BY guild_id) latest ON reports.guild_id=latest.guild_id "
                + "AND reports.report_date=latest.latest_date WHERE reports.server=? ORDER BY reports.guild_id";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, server);
            statement.setString(2, server);
            try (ResultSet results = statement.executeQuery()) {
                List<String> guildIds = new ArrayList<>();
                while (results.next()) {
                    JsonNode report = mapper.readTree(results.getString(2));
                    if (report.path("guild_name").asText().equalsIgnoreCase(guildName)) {
                        guildIds.add(results.getString(1));
                    }
                }
                return List.copyOf(guildIds);
            }
        } catch (SQLException | IOException exception) {
            throw new StorageException("Cannot resolve guild from saved reports", exception);
        }
    }

    /** Rebuilds daily history without duplicate append entries; rename preserves the old file on failure. */
    public Path exportText(Connection connection, String server, String guildId, Path directory) {
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            Path destination = directory.resolve("attendance-" + server + "-" + sha256(guildId).substring(0, 16) + ".txt");
            temporary = Files.createTempFile(directory, ".attendance-", ".tmp");
            try (PreparedStatement statement = connection.prepareStatement("SELECT report_json FROM attendance_reports "
                    + "WHERE server=? AND guild_id=? ORDER BY report_date");
                 BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                statement.setString(1, server);
                statement.setString(2, guildId);
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        writer.write(renderReport(mapper.readTree(results.getString(1))));
                    }
                }
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return destination;
        } catch (SQLException | IOException exception) {
            throw new StorageException("Cannot export attendance text", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // A failed temporary-file cleanup must not obscure the original export failure.
                }
            }
        }
    }

    public String renderReport(DailyReport report) {
        return renderReport(mapper.valueToTree(report));
    }

    static String renderReport(JsonNode report) {
        StringBuilder text = new StringBuilder();
        text.append("=== ").append(report.path("date").asText()).append(" UTC | ")
                .append(cleanText(report.path("guild_name").asText())).append(" | ")
                .append(report.path("server").asText()).append(" ===\n")
                .append("统计截止：").append(report.path("cutoff_utc").asText()).append(" | ")
                .append(report.path("is_partial").asBoolean() ? "当天快照（未结束）" : "全天").append('\n')
                .append("成员快照时间：").append(report.path("roster_observed_at_utc").asText())
                .append("（未参战按此时在会成员计算）\n")
                .append("有效战役：").append(report.path("battles").size()).append(" | 门槛：本会 ")
                .append(report.path("thresholds").path("min_guild_players").asInt()).append(" 人、战役 ")
                .append(report.path("thresholds").path("min_battle_players").asInt()).append(" 人\n")
                .append("说明：仅统计官方战报所记录的玩家；未被记录不等于实际没有参加。\n");
        List<JsonNode> members = new ArrayList<>();
        report.path("members").forEach(members::add);
        members.sort(Comparator.comparing((JsonNode member) -> member.path("name").asText().toLowerCase(Locale.ROOT))
                .thenComparing(member -> member.path("player_id").asText()));
        appendGroup(text, "在会参战", members.stream().filter(member -> member.path("in_roster").asBoolean()
                && member.path("attended").asBoolean()).toList());
        appendGroup(text, "在会未记录参战", members.stream().filter(member -> member.path("in_roster").asBoolean()
                && !member.path("attended").asBoolean()).toList());
        appendGroup(text, "战报参战但当前不在会", members.stream().filter(member -> !member.path("in_roster").asBoolean()
                && member.path("attended").asBoolean()).toList());
        List<String> battleIds = new ArrayList<>();
        report.path("battles").forEach(battle -> battleIds.add(battle.path("battle_id").asText()));
        return text.append("战役 ID：").append(String.join(", ", battleIds)).append("\n\n").toString();
    }

    private static void appendGroup(StringBuilder text, String label, List<JsonNode> members) {
        text.append(label).append('（').append(members.size()).append(" 人）：\n");
        if (members.isEmpty()) {
            text.append("  无\n");
        } else {
            for (JsonNode member : members) {
                text.append("  ").append(cleanText(member.path("name").asText())).append(" [")
                        .append(cleanText(member.path("player_id").asText())).append("]\n");
            }
        }
    }

    private static String cleanText(String value) {
        return value.replaceAll("(?U)\\s+", " ").strip();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void bindDay(PreparedStatement statement, String server, String guildId, LocalDate date) throws SQLException {
        statement.setString(1, server);
        statement.setString(2, guildId);
        statement.setDate(3, Date.valueOf(date));
    }

    @FunctionalInterface
    private interface SqlOperation {
        void execute() throws SQLException, IOException;
    }

    private static void inTransaction(Connection connection, SqlOperation operation) {
        boolean restoreAutoCommit;
        try {
            restoreAutoCommit = connection.getAutoCommit();
            if (restoreAutoCommit) {
                connection.setAutoCommit(false);
            }
        } catch (SQLException exception) {
            throw new StorageException("Cannot start attendance transaction", exception);
        }
        Throwable primary = null;
        try {
            operation.execute();
            connection.commit();
        } catch (SQLException | IOException | RuntimeException | Error failure) {
            primary = failure;
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
                restoreAutoCommit = false;
                try {
                    connection.abort(Runnable::run);
                } catch (SQLException abortFailure) {
                    failure.addSuppressed(abortFailure);
                }
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new StorageException("Attendance transaction failed", failure);
        } finally {
            if (restoreAutoCommit) {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException resetFailure) {
                    if (primary != null) {
                        primary.addSuppressed(resetFailure);
                    } else {
                        throw new StorageException("Cannot restore connection state", resetFailure);
                    }
                }
            }
        }
    }
}
