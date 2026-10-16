package com.albion.attendance;

import com.albion.attendance.core.AttendanceCollector;
import com.albion.attendance.core.CollectionSettings;
import com.albion.attendance.core.DailyReport;
import com.albion.attendance.core.Guild;
import com.albion.attendance.storage.AttendanceRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AttendanceService {
    private static final Logger LOG = LoggerFactory.getLogger(AttendanceService.class);
    private final AttendanceProperties properties;
    private final AttendanceCollector collector;
    private final AttendanceRepository repository;
    private final Clock clock;

    public AttendanceService(AttendanceProperties properties, AttendanceCollector collector,
                             AttendanceRepository repository, Clock clock) {
        this.properties = properties;
        this.collector = collector;
        this.repository = repository;
        this.clock = clock;
    }

    private CollectionSettings resolveSettings() {
        CollectionSettings settings = properties.collectionSettings();
        Guild guild = collector.resolveGuild(settings);
        LOG.info("Resolved guild: {} ({})", guild.name(), guild.id());
        return new CollectionSettings(settings.server(), guild.id(), guild.name(), settings.minGuildPlayers(),
                settings.minBattlePlayers(), settings.timeoutSeconds(), settings.retries(), settings.retrySeconds(),
                settings.pageLimit(), settings.maxPages());
    }

    public Path fetch(LocalDate requestedDate) throws Exception {
        Instant now = clock.instant();
        LocalDate day = requestedDate == null ? now.atOffset(ZoneOffset.UTC).toLocalDate() : requestedDate;
        CollectionSettings settings = resolveSettings();
        return repository.withGuildLock(settings.server(), settings.guildId(), connection -> {
            DailyReport report = collector.collect(settings, day, now);
            repository.saveReport(connection, report);
            LOG.info("Saved report {}: {} battles", day, report.battles().size());
            return repository.exportText(connection, settings.server(), settings.guildId(), outputDirectory());
        });
    }

    public void collectScheduled(Instant now) throws Exception {
        LocalDate today = now.atOffset(ZoneOffset.UTC).toLocalDate();
        CollectionSettings settings = resolveSettings();
        repository.withGuildLock(settings.server(), settings.guildId(), connection -> {
            if (!repository.hasRun(connection, settings.server(), settings.guildId(), today)) {
                if (properties.finalizePreviousDay()) {
                    repository.saveReport(connection, collector.collect(settings, today.minusDays(1), now));
                }
                repository.saveReport(connection, collector.collect(settings, today, now));
            }
            repository.exportText(connection, settings.server(), settings.guildId(), outputDirectory());
            repository.markRun(connection, settings.server(), settings.guildId(), today);
            return null;
        });
        LOG.info("Daily reports and TXT ready for {} UTC", today);
    }

    public Path export() throws Exception {
        String guildId = queryGuildId();
        return repository.withGuildLock(properties.server(), guildId, connection ->
                repository.exportText(connection, properties.server(), guildId, outputDirectory()));
    }

    public String queryGuildId() {
        if (!properties.guildId().isBlank()) return properties.guildId();
        List<String> ids = repository.findGuildIdsByName(properties.server(), properties.guildName());
        if (ids.isEmpty()) throw new NoSuchElementException("No collected reports for this guild");
        if (ids.size() != 1) throw new IllegalArgumentException("Guild name is ambiguous; configure guild-id");
        return ids.getFirst();
    }

    public List<LocalDate> dates(int limit) {
        if (limit < 1 || limit > 366) throw new IllegalArgumentException("limit must be 1..366");
        return repository.listDates(properties.server(), queryGuildId(), limit);
    }

    public DailyReport report(LocalDate day) {
        return repository.findReport(properties.server(), queryGuildId(), day)
                .orElseThrow(() -> new NoSuchElementException("Report does not exist"));
    }

    public DailyReport latestReport() {
        List<LocalDate> dates = dates(1);
        if (dates.isEmpty()) throw new NoSuchElementException("No collected reports");
        return report(dates.getFirst());
    }

    public Path textFile() throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(queryGuildId().getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        return outputDirectory().resolve("attendance-" + properties.server() + "-" + hash + ".txt");
    }

    private Path outputDirectory() { return Path.of(properties.outputDirectory()).toAbsolutePath().normalize(); }
}
