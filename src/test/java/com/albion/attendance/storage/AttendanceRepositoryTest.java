package com.albion.attendance.storage;

import com.albion.attendance.core.DailyReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AttendanceRepositoryTest {
    static final ObjectMapper MAPPER = new ObjectMapper();

    static DailyReport sample() {
        return new DailyReport(1, "2026-10-16", "2026-10-16T12:00:00Z", "2026-10-16T12:00:00Z",
                "2026-10-16T00:00:00Z", "2026-10-16T12:00:00Z", true, "europe", "guild", "CHINAFIGHT",
                new DailyReport.Thresholds(2, 3), "current_snapshot", "2026-10-16T12:00:00Z",
                List.of(new DailyReport.Member("present", "Present", true, true, List.of(10L)),
                        new DailyReport.Member("absent", "Absent", false, true, List.of()),
                        new DailyReport.Member("former", "Former", true, false, List.of(10L))),
                List.of(new DailyReport.Battle(10, "2026-10-16T11:00:00Z", "2026-10-16T11:05:00Z",
                        3, 2, List.of("present", "former"))), new DailyReport.Counts(2, 2, 1, 1));
    }

    @Test
    void rendersExistingPublicGroupsAndSnapshot() {
        AttendanceRepository repository = new AttendanceRepository(mock(DataSource.class), MAPPER);
        String text = repository.renderReport(sample());
        assertTrue(text.contains("在会参战（1 人）：\n  Present [present]"));
        assertTrue(text.contains("在会未记录参战（1 人）：\n  Absent [absent]"));
        assertTrue(text.contains("战报参战但当前不在会（1 人）：\n  Former [former]"));
        assertTrue(text.contains("当天快照（未结束）"));
        assertTrue(text.endsWith("战役 ID：10\n\n"));
    }

    @Test
    void textSanitizesMultilineNamesAndSortsEmptyGroups() throws Exception {
        var payload = MAPPER.valueToTree(sample());
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload).put("guild_name", "Guild\n\tName");
        ((com.fasterxml.jackson.databind.node.ArrayNode) payload.path("members")).removeAll();
        String text = AttendanceRepository.renderReport(payload);
        assertTrue(text.contains("UTC | Guild Name | europe"));
        assertTrue(text.contains("在会参战（0 人）：\n  无\n"));
    }

    @Test
    void oldSnakeCaseJsonStillRoundTrips() throws Exception {
        String json = MAPPER.writeValueAsString(sample());
        assertTrue(json.contains("\"guild_id\":\"guild\""));
        assertTrue(json.contains("\"is_partial\":true"));
        assertFalse(json.contains("guildId"));
        assertEquals(sample(), MAPPER.readValue(json, DailyReport.class));
    }

    @Test
    void holdsOneConnectionUntilCallbackAndReleasesBeforeClosing() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement acquire = mock(PreparedStatement.class);
        PreparedStatement release = mock(PreparedStatement.class);
        ResultSet acquired = scalar(1);
        ResultSet released = scalar(1);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT GET_LOCK(?, 0)")).thenReturn(acquire);
        when(connection.prepareStatement("SELECT RELEASE_LOCK(?)")).thenReturn(release);
        when(acquire.executeQuery()).thenReturn(acquired);
        when(release.executeQuery()).thenReturn(released);
        AttendanceRepository repository = new AttendanceRepository(dataSource, MAPPER);
        String result = repository.withGuildLock("europe", "guild", owned -> {
            assertSame(connection, owned);
            verify(release, never()).executeQuery();
            verify(connection, never()).close();
            return "saved and exported";
        });
        assertEquals("saved and exported", result);
        InOrder order = inOrder(acquire, connection, release);
        order.verify(acquire).executeQuery();
        order.verify(connection).rollback();
        order.verify(release).executeQuery();
        order.verify(connection).close();
        verify(dataSource, times(1)).getConnection();
    }

    @Test
    void cleanupFailureDoesNotHideCollectionFailure() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement acquire = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT GET_LOCK(?, 0)")).thenReturn(acquire);
        ResultSet acquired = scalar(1);
        when(acquire.executeQuery()).thenReturn(acquired);
        when(connection.prepareStatement("SELECT RELEASE_LOCK(?)")).thenThrow(new SQLException("release failed"));
        doThrow(new SQLException("rollback failed")).when(connection).rollback();
        RuntimeException primary = new RuntimeException("collection failed");
        AttendanceRepository repository = new AttendanceRepository(dataSource, MAPPER);
        RuntimeException actual = assertThrows(RuntimeException.class, () -> repository.withGuildLock("europe", "guild", owned -> {
            throw primary;
        }));
        assertSame(primary, actual);
        assertEquals("rollback failed", actual.getSuppressed()[0].getMessage());
        assertEquals("release failed", actual.getSuppressed()[0].getSuppressed()[0].getMessage());
        verify(connection).close();
    }

    @Test
    void busyLockSkipsCallbackAndClosesConnection() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement acquire = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT GET_LOCK(?, 0)")).thenReturn(acquire);
        ResultSet busy = scalar(0);
        when(acquire.executeQuery()).thenReturn(busy);
        AttendanceRepository repository = new AttendanceRepository(dataSource, MAPPER);
        assertThrows(IllegalStateException.class, () -> repository.withGuildLock("europe", "guild", owned -> {
            fail("A busy lock must not collect");
            return null;
        }));
        verify(connection).close();
        verify(connection, never()).prepareStatement("SELECT RELEASE_LOCK(?)");
    }

    private static ResultSet scalar(int value) throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getInt(1)).thenReturn(value);
        return resultSet;
    }
}
