package com.albion.attendance;

import com.albion.attendance.core.AttendanceCollector;
import com.albion.attendance.core.DailyReport;
import com.albion.attendance.core.Guild;
import com.albion.attendance.storage.AttendanceRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttendanceServiceTest {
    private final AttendanceRepository repository = mock(AttendanceRepository.class);
    private final AttendanceCollector collector = mock(AttendanceCollector.class);
    private final Connection connection = mock(Connection.class);
    private final Instant now = Instant.parse("2026-10-16T23:30:00Z");

    private AttendanceService service() throws Exception {
        when(repository.withGuildLock(eq("europe"), eq("guild"), any())).thenAnswer(invocation -> {
            AttendanceRepository.GuildOperation<?> operation = invocation.getArgument(2);
            return operation.execute(connection);
        });
        when(collector.resolveGuild(any())).thenReturn(new Guild("guild", "Test Guild"));
        return new AttendanceService(AttendanceSchedulerTest.properties(true), collector, repository,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test void scheduledCollectsYesterdayThenTodayAndMarksOnlyAfterExport() throws Exception {
        var service = service();
        DailyReport report = mock(DailyReport.class);
        when(collector.collect(any(), any(), eq(now))).thenReturn(report);
        service.collectScheduled(now);
        var order = inOrder(collector, repository);
        order.verify(collector).collect(any(), eq(LocalDate.of(2026, 10, 15)), eq(now));
        order.verify(repository).saveReport(connection, report);
        order.verify(collector).collect(any(), eq(LocalDate.of(2026, 10, 16)), eq(now));
        order.verify(repository).saveReport(connection, report);
        order.verify(repository).exportText(eq(connection), eq("europe"), eq("guild"), any());
        order.verify(repository).markRun(connection, "europe", "guild", LocalDate.of(2026, 10, 16));
    }

    @Test void exportFailureDoesNotMarkRunComplete() throws Exception {
        var service = service();
        when(collector.collect(any(), any(), any())).thenReturn(mock(DailyReport.class));
        when(repository.exportText(any(), any(), any(), any())).thenThrow(new RuntimeException("disk full"));
        assertThrows(RuntimeException.class, () -> service.collectScheduled(now));
        verify(repository, never()).markRun(any(), any(), any(), any());
    }

    @Test void completedScheduleRepairsExportWithoutCollectingAgain() throws Exception {
        var service = service();
        when(repository.hasRun(connection, "europe", "guild", LocalDate.of(2026, 10, 16))).thenReturn(true);
        service.collectScheduled(now);
        verify(collector, never()).collect(any(), any(), any());
        verify(repository).exportText(eq(connection), eq("europe"), eq("guild"), any());
    }

    @Test void offlineExportDoesNotContactGameInfo() throws Exception {
        var service = service();
        when(repository.exportText(any(), any(), any(), any())).thenReturn(Path.of("report.txt"));
        assertEquals(Path.of("report.txt"), service.export());
        verifyNoInteractions(collector);
    }
}
