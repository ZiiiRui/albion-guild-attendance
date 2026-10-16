package com.albion.attendance;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class AttendanceSchedulerTest {
    static AttendanceProperties properties(boolean finalizeYesterday) {
        return new AttendanceProperties("europe", "guild", "", "23:30", 5, 10, "output", true,
                finalizeYesterday, 300, new AttendanceProperties.Api(30, 3, 1, 50, 200));
    }

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-16T23:29:59Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    @Test void waitsForUtcTimeAndRunsOncePerDay() throws Exception {
        var service = mock(AttendanceService.class);
        var clock = new MutableClock();
        var scheduler = new AttendanceScheduler(properties(true), service, clock);
        scheduler.tick();
        verifyNoInteractions(service);
        clock.now = Instant.parse("2026-10-16T23:30:00Z");
        scheduler.tick();
        scheduler.tick();
        verify(service, times(1)).collectScheduled(clock.now);
        clock.now = Instant.parse("2026-10-17T23:31:00Z");
        scheduler.tick();
        verify(service).collectScheduled(clock.now);
    }

    @Test void retriesFailureWithoutMarkingDayComplete() throws Exception {
        var service = mock(AttendanceService.class);
        var clock = new MutableClock();
        clock.now = Instant.parse("2026-10-16T23:30:00Z");
        doThrow(new RuntimeException("temporary API failure")).doNothing().when(service).collectScheduled(any());
        var scheduler = new AttendanceScheduler(properties(true), service, clock);
        scheduler.tick();
        clock.now = Instant.parse("2026-10-16T23:34:59Z");
        scheduler.tick();
        verify(service, times(1)).collectScheduled(any());
        clock.now = Instant.parse("2026-10-16T23:35:00Z");
        scheduler.tick();
        scheduler.tick();
        verify(service, times(2)).collectScheduled(any());
    }
}
