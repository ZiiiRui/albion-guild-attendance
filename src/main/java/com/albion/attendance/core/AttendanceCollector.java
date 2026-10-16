package com.albion.attendance.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class AttendanceCollector {
    private final GameInfoClient client;

    public AttendanceCollector() { this(new GameInfoClient()); }
    public AttendanceCollector(GameInfoClient client) { this.client = client; }
    public Guild resolveGuild(CollectionSettings settings) { return client.resolveGuild(settings); }

    public DailyReport collect(CollectionSettings settings, LocalDate date, Instant now) {
        Instant start = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant nextDay = start.plus(Duration.ofDays(1));
        if (start.isAfter(now)) throw new IllegalArgumentException("cannot collect a future date");
        if (start.isBefore(now.minus(Duration.ofDays(7))))
            throw new IllegalArgumentException("requested day starts outside the supported one-week collection window");
        Instant end = nextDay.isAfter(now) ? now : nextDay;
        Guild guild = resolveGuild(settings);
        JsonNode roster = client.guildMembers(settings, guild.id());
        if (roster == null || !roster.isArray()) throw new CollectionException("invalid guild roster");
        Map<String, DailyReport.Member> members = new LinkedHashMap<>();
        for (JsonNode player : roster) {
            String id = GameInfoClient.text(player.get("Id"), "roster player id");
            String name = GameInfoClient.text(player.get("Name"), "roster player name");
            if (members.containsKey(id)) throw new CollectionException("roster contains duplicate player IDs");
            JsonNode membership = player.get("GuildId");
            if (membership != null && !membership.isNull()
                    && (!membership.isTextual() || !guild.id().equals(membership.asText())))
                throw new CollectionException("roster member belongs to another guild");
            members.put(id, new DailyReport.Member(id, name, false, true, List.of()));
        }
        List<DailyReport.Battle> battles = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (JsonNode battle : client.guildBattles(settings, guild.id(), start, end)) {
            long id = GameInfoClient.battleId(battle);
            if (!seen.add(id)) throw new CollectionException("duplicate battle ID");
            Instant stamp = GameInfoClient.timestamp(battle.get("startTime"));
            if (stamp.isBefore(start) || !stamp.isBefore(end))
                throw new CollectionException("battle is outside the requested time interval");
            JsonNode players = battle.get("players");
            if (players == null || !players.isObject() || players.isEmpty())
                throw new CollectionException("battle has no complete player map");
            Map<String, String> guildPlayers = new LinkedHashMap<>();
            players.fields().forEachRemaining(entry -> {
                if (entry.getKey().isBlank()) throw new CollectionException("battle player id is missing");
                JsonNode player = entry.getValue();
                JsonNode membership = player.get("guildId");
                if (!player.isObject() || membership == null || (!membership.isNull() && !membership.isTextual()))
                    throw new CollectionException("battle player guild identity is missing");
                if (guild.id().equals(membership.asText()))
                    guildPlayers.put(entry.getKey(), GameInfoClient.text(player.get("name"), "battle player name"));
            });
            if (guildPlayers.isEmpty() || players.size() < settings.minBattlePlayers()
                    || guildPlayers.size() < settings.minGuildPlayers()) continue;
            Instant endTime = GameInfoClient.timestamp(battle.get("endTime"));
            if (endTime.isBefore(stamp)) throw new CollectionException("battle end precedes its start");
            battles.add(new DailyReport.Battle(id, stamp.toString(), endTime.toString(), players.size(),
                    guildPlayers.size(), guildPlayers.keySet().stream().sorted().toList()));
            guildPlayers.forEach((playerId, name) -> {
                DailyReport.Member member = members.get(playerId);
                List<Long> ids = new ArrayList<>(member == null ? List.of() : member.battleIds());
                ids.add(id);
                ids.sort(Long::compareTo);
                members.put(playerId, new DailyReport.Member(playerId, member == null ? name : member.name(),
                        true, member != null && member.inRoster(), ids));
            });
        }
        List<DailyReport.Member> ordered = members.values().stream()
                .sorted(Comparator.comparing((DailyReport.Member member) -> member.name().toLowerCase(Locale.ROOT))
                        .thenComparing(DailyReport.Member::playerId)).toList();
        battles.sort(Comparator.comparingLong(DailyReport.Battle::battleId));
        int attended = (int) ordered.stream().filter(DailyReport.Member::attended).count();
        int absent = (int) ordered.stream().filter(member -> member.inRoster() && !member.attended()).count();
        int outside = (int) ordered.stream().filter(member -> !member.inRoster()).count();
        return new DailyReport(1, date.toString(), end.toString(), now.toString(), start.toString(), end.toString(),
                end.isBefore(nextDay), settings.server(), guild.id(), guild.name(),
                new DailyReport.Thresholds(settings.minGuildPlayers(), settings.minBattlePlayers()),
                "current_snapshot", now.toString(), ordered, battles,
                new DailyReport.Counts(roster.size(), attended, absent, outside));
    }
}
