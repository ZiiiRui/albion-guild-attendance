package com.albion.attendance.storage;

import com.albion.attendance.core.DailyReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "ATTENDANCE_TEST_DB", matches = "1")
class AttendanceRepositoryMySqlTest {
    private DriverManagerDataSource dataSource;
    private AttendanceRepository repository;
    @TempDir Path directory;

    @BeforeEach
    void initializeDedicatedDatabase() throws Exception {
        String url = System.getenv().getOrDefault("TEST_DB_URL", "jdbc:mysql://127.0.0.1:3306/attendance_test");
        if (!url.matches("jdbc:mysql://[^/]+/attendance_test(?:\\?.*)?")) {
            throw new IllegalArgumentException("MySQL tests require the dedicated attendance_test database");
        }
        dataSource = new DriverManagerDataSource(url, System.getenv().getOrDefault("TEST_DB_USER", "root"),
                System.getenv("TEST_DB_PASSWORD"));
        repository = new AttendanceRepository(dataSource, AttendanceRepositoryTest.MAPPER);
        repository.initialize();
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            for (String table : List.of("attendance_members", "attendance_reports", "attendance_runs")) {
                statement.executeUpdate("DELETE FROM " + table);
            }
        }
    }

    @Test
    void saveAndExportAreIdempotentAndRejectStaleSnapshots() throws Exception {
        DailyReport report = AttendanceRepositoryTest.sample();
        repository.withGuildLock("europe", "guild", connection -> {
            repository.saveReport(connection, report);
            repository.saveReport(connection, report);
            Path exported = repository.exportText(connection, "europe", "guild", directory);
            byte[] first = Files.readAllBytes(exported);
            assertArrayEquals(first, Files.readAllBytes(repository.exportText(connection, "europe", "guild", directory)));
            assertEquals(1, new String(first, java.nio.charset.StandardCharsets.UTF_8).split("=== 2026-10-16 UTC", -1).length - 1);
            DailyReport older = changed(report, report.date(), "2026-10-16T11:59:59.999999Z", report.guildName(), report.members());
            assertThrows(IllegalArgumentException.class, () -> repository.saveReport(connection, older));
            assertFalse(repository.hasRun(connection, "europe", "guild", LocalDate.parse(report.date())));
            repository.markRun(connection, "europe", "guild", LocalDate.parse(report.date()));
            assertTrue(repository.hasRun(connection, "europe", "guild", LocalDate.parse(report.date())));
            return null;
        });
        assertEquals(report, repository.findReport("europe", "guild", LocalDate.parse(report.date())).orElseThrow());
        assertEquals(List.of(LocalDate.parse(report.date())), repository.listDates("europe", "guild", 30));
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement();
             var results = statement.executeQuery("SELECT COUNT(*) FROM attendance_members")) {
            assertTrue(results.next());
            assertEquals(3, results.getInt(1));
        }
    }

    @Test
    void duplicateMemberRollsBackWholeReport() throws Exception {
        DailyReport report = AttendanceRepositoryTest.sample();
        repository.withGuildLock("europe", "guild", connection -> {
            repository.saveReport(connection, report);
            var members = new ArrayList<>(report.members());
            members.add(members.getFirst());
            DailyReport invalid = changed(report, report.date(), report.cutoffUtc(), "Replacement", members);
            assertThrows(StorageException.class, () -> repository.saveReport(connection, invalid));
            return null;
        });
        assertEquals(report, repository.findReport("europe", "guild", LocalDate.parse(report.date())).orElseThrow());
    }

    @Test
    void failedExportLeavesPreviousFileAndRetryRepairsIt() throws Exception {
        DailyReport report = AttendanceRepositoryTest.sample();
        repository.withGuildLock("europe", "guild", connection -> {
            repository.saveReport(connection, report);
            Path destination = repository.exportText(connection, "europe", "guild", directory);
            byte[] original = Files.readAllBytes(destination);
            repository.saveReport(connection, changed(report, report.date(), report.cutoffUtc(), "Updated Guild", report.members()));
            Path blocker = directory.resolve("not-a-directory");
            Files.writeString(blocker, "blocker");
            assertThrows(StorageException.class, () -> repository.exportText(connection, "europe", "guild", blocker));
            assertArrayEquals(original, Files.readAllBytes(destination));
            repository.exportText(connection, "europe", "guild", directory);
            assertTrue(Files.readString(destination).contains("Updated Guild"));
            return null;
        });
    }

    @Test
    void guildNameResolutionUsesLatestReportAndExactCaseInsensitiveName() throws Exception {
        DailyReport report = AttendanceRepositoryTest.sample();
        repository.withGuildLock("europe", "guild", connection -> {
            repository.saveReport(connection, report);
            repository.saveReport(connection, changed(report, "2026-10-15", "2026-10-16T00:00:00Z", "Previous Name", report.members()));
            return null;
        });
        assertEquals(List.of("guild"), repository.findGuildIdsByName("europe", "chinafight"));
        assertEquals(List.of(), repository.findGuildIdsByName("europe", "Previous Name"));
        assertEquals(List.of(), repository.findGuildIdsByName("asia", "CHINAFIGHT"));
    }

    @Test
    void mysqlLockExcludesOtherCollectorsAndReleasesAfterFailure() throws Exception {
        repository.withGuildLock("europe", "guild", first -> {
            assertThrows(IllegalStateException.class, () -> repository.withGuildLock("europe", "guild", second -> null));
            return null;
        });
        assertThrows(IllegalArgumentException.class, () -> repository.withGuildLock("europe", "guild", connection -> {
            throw new IllegalArgumentException("failure");
        }));
        assertEquals("released", repository.withGuildLock("europe", "guild", connection -> "released"));
    }

    private static DailyReport changed(DailyReport report, String date, String cutoff, String guildName,
                                       List<DailyReport.Member> members) {
        return new DailyReport(report.schemaVersion(), date, cutoff, report.collectedAtUtc(), report.periodStartUtc(),
                report.periodEndUtc(), report.isPartial(), report.server(), report.guildId(), guildName, report.thresholds(),
                report.rosterScope(), report.rosterObservedAtUtc(), members, report.battles(), report.counts());
    }
}
