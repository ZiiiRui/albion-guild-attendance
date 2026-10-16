package com.albion.attendance.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AttendanceCollectorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);
    private static final CollectionSettings SETTINGS = settings(2, 3);

    private static CollectionSettings settings(int guildFloor, int battleFloor) {
        return new CollectionSettings("asia", "guild", "", guildFloor, battleFloor, 30, 0, 0, 50, 200);
    }

    static ObjectNode battle(long id, String stamp) {
        ObjectNode battle = MAPPER.createObjectNode().put("id", id).put("startTime", stamp).put("endTime", stamp);
        ObjectNode players = battle.putObject("players");
        players.putObject("one").put("name", "Alice").put("guildId", "guild");
        players.putObject("old").put("name", "Former").put("guildId", "guild");
        players.putObject("enemy").put("name", "Enemy").put("guildId", "other");
        return battle;
    }

    private static ArrayNode roster() {
        ArrayNode roster = MAPPER.createArrayNode();
        roster.addObject().put("Id", "one").put("Name", "Alice").put("GuildId", "guild");
        roster.addObject().put("Id", "two").put("Name", "Bob").put("GuildId", "guild");
        return roster;
    }

    private static AttendanceCollector collector(JsonNode roster, List<JsonNode> battles) {
        return new AttendanceCollector(new GameInfoClient((uri, timeout) -> {
            throw new AssertionError("fake client must not access the network");
        }) {
            @Override public Guild resolveGuild(CollectionSettings settings) { return new Guild("guild", "Test Guild"); }
            @Override public JsonNode guildMembers(CollectionSettings settings, String guildId) { return roster; }
            @Override public List<JsonNode> guildBattles(CollectionSettings settings, String guildId, Instant start, Instant end) {
                return battles;
            }
        });
    }

    @Test void thresholdBoundaryDeduplicatesPlayersAndRetainsFormerMembers() throws Exception {
        DailyReport report = collector(roster(), List.of(battle(1, "2026-10-16T01:00:00Z"),
                battle(2, "2026-10-16T02:00:00Z"))).collect(SETTINGS, TODAY, NOW);
        assertEquals(new DailyReport.Counts(2, 2, 1, 1), report.counts());
        assertEquals(List.of(1L, 2L), report.members().getFirst().battleIds());
        DailyReport.Member former = report.members().stream().filter(m -> m.playerId().equals("old")).findFirst().orElseThrow();
        assertTrue(former.attended());
        assertFalse(former.inRoster());
        assertTrue(report.isPartial());
        assertEquals("2026-10-16T12:00:00Z", report.cutoffUtc());
        assertEquals("current_snapshot", report.rosterScope());
        JsonNode json = MAPPER.valueToTree(report);
        assertEquals(1, json.path("schema_version").asInt());
        assertTrue(json.path("is_partial").asBoolean());
        assertTrue(json.path("members").get(0).has("player_id"));
        assertTrue(json.path("battles").get(0).has("start_time_utc"));
        assertEquals(1, json.path("counts").path("attended_outside_roster").asInt());
        assertEquals(report, MAPPER.treeToValue(json, DailyReport.class));
    }

    @Test void eachBattleMustMeetBothFloors() {
        ObjectNode tooSmall = battle(1, "2026-10-16T01:00:00Z");
        ((ObjectNode) tooSmall.get("players")).remove("enemy");
        ObjectNode tooFewGuild = battle(2, "2026-10-16T01:00:00Z");
        ((ObjectNode) tooFewGuild.path("players").path("old")).put("guildId", "other");
        DailyReport report = collector(roster(), List.of(tooSmall, tooFewGuild)).collect(SETTINGS, TODAY, NOW);
        assertTrue(report.battles().isEmpty());
        assertEquals(2, report.counts().absent());
    }

    @Test void nullEnemyGuildIsAllowedButMissingGuildIdentityFails() {
        ObjectNode item = battle(1, "2026-10-16T01:00:00Z");
        ObjectNode enemy = (ObjectNode) item.path("players").path("enemy");
        enemy.putNull("guildId");
        assertEquals(2, collector(roster(), List.of(item)).collect(SETTINGS, TODAY, NOW).counts().attended());
        enemy.remove("guildId");
        assertThrows(CollectionException.class, () -> collector(roster(), List.of(item)).collect(SETTINGS, TODAY, NOW));
    }

    @Test void zeroFloorsStillRequireActualGuildParticipation() {
        ObjectNode item = battle(1, "2026-10-16T01:00:00Z");
        ((ObjectNode) item.get("players")).remove(List.of("one", "old"));
        DailyReport report = collector(roster(), List.of(item)).collect(settings(0, 0), TODAY, NOW);
        assertTrue(report.battles().isEmpty());
    }

    @Test void previousDayIsFullWithCurrentRosterSnapshot() {
        DailyReport report = collector(roster(), List.of(battle(1, "2026-10-15T23:59:59Z")))
                .collect(SETTINGS, TODAY.minusDays(1), NOW);
        assertFalse(report.isPartial());
        assertEquals("2026-10-16T00:00:00Z", report.periodEndUtc());
        assertEquals(NOW.toString(), report.rosterObservedAtUtc());
    }

    @Test void invalidRosterBattleAndDateCannotBecomeAbsence() {
        ArrayNode duplicate = roster();
        duplicate.add(duplicate.get(0).deepCopy());
        assertThrows(CollectionException.class, () -> collector(duplicate, List.of()).collect(SETTINGS, TODAY, NOW));
        ArrayNode missingIdentity = MAPPER.createArrayNode();
        missingIdentity.addObject().put("Name", "Unknown");
        assertThrows(CollectionException.class, () -> collector(missingIdentity, List.of()).collect(SETTINGS, TODAY, NOW));
        ObjectNode item = battle(1, "2026-10-16T01:00:00Z");
        assertThrows(CollectionException.class, () -> collector(roster(), List.of(item, item)).collect(SETTINGS, TODAY, NOW));
        for (String stamp : List.of("2026-10-15T23:59:59Z", "2026-10-16T12:00:00Z", "2026-10-16T01:00:00")) {
            assertThrows(CollectionException.class, () -> collector(roster(), List.of(battle(1, stamp))).collect(SETTINGS, TODAY, NOW));
        }
        AttendanceCollector empty = collector(roster(), List.of());
        assertThrows(IllegalArgumentException.class, () -> empty.collect(SETTINGS, TODAY.plusDays(1), NOW));
        assertThrows(IllegalArgumentException.class, () -> empty.collect(SETTINGS, TODAY.minusDays(7), NOW));
    }

    @Test void deserializesLegacyPythonSnakeCaseJson() throws Exception {
        String json = """
                {"schema_version":1,"date":"2026-10-16","cutoff_utc":"2026-10-16T12:00:00Z",
                 "collected_at_utc":"2026-10-16T12:00:00Z","period_start_utc":"2026-10-16T00:00:00Z",
                 "period_end_utc":"2026-10-16T12:00:00Z","is_partial":true,"server":"asia",
                 "guild_id":"guild","guild_name":"Guild","thresholds":{"min_guild_players":2,"min_battle_players":3},
                 "roster_scope":"current_snapshot","roster_observed_at_utc":"2026-10-16T12:00:00Z",
                 "members":[{"player_id":"one","name":"Alice","attended":true,"in_roster":true,"battle_ids":[123]}],
                 "battles":[{"battle_id":123,"start_time_utc":"2026-10-16T01:00:00Z",
                 "end_time_utc":"2026-10-16T02:00:00Z","total_players":3,"guild_players":2,"player_ids":["one"]}],
                 "counts":{"roster":1,"attended":1,"absent":0,"attended_outside_roster":0}}
                """;
        DailyReport report = MAPPER.readValue(json, DailyReport.class);
        assertEquals(List.of(123L), report.members().getFirst().battleIds());
        assertEquals(MAPPER.readTree(json), MAPPER.readTree(MAPPER.writeValueAsString(report)));
    }
}
