package com.albion.attendance;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "attendance.command", havingValue = "serve", matchIfMissing = true)
public class AttendanceScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(AttendanceScheduler.class);
    private final AttendanceProperties properties;
    private final AttendanceService service;
    private final Clock clock;
    private LocalDate completedDay;
    private Instant nextAttempt = Instant.MIN;

    public AttendanceScheduler(AttendanceProperties properties, AttendanceService service, Clock clock) {
        this.properties = properties;
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(initialDelay = 1000, fixedDelay = 30000)
    public void tick() {
        if (!properties.schedulingEnabled()) return;
        Instant now = clock.instant();
        LocalDate today = now.atOffset(ZoneOffset.UTC).toLocalDate();
        Instant scheduled = today.atTime(LocalTime.parse(properties.fetchTimeUtc())).toInstant(ZoneOffset.UTC);
        if (today.equals(completedDay) || now.isBefore(scheduled) || now.isBefore(nextAttempt)) return;
        try {
            service.collectScheduled(now);
            completedDay = today;
        } catch (Exception error) {
            LOG.error("Daily collection failed; retry after {} seconds", properties.retryDelaySeconds(), error);
            nextAttempt = clock.instant().plusSeconds(properties.retryDelaySeconds());
        }
    }
}
