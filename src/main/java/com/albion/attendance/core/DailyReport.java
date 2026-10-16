package com.albion.attendance.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DailyReport(int schemaVersion, String date, String cutoffUtc, String collectedAtUtc,
                          String periodStartUtc, String periodEndUtc,
                          @JsonProperty("is_partial") boolean isPartial,
                          String server, String guildId, String guildName,
                          Thresholds thresholds, String rosterScope, String rosterObservedAtUtc,
                          List<Member> members, List<Battle> battles, Counts counts) {
    public DailyReport {
        members = List.copyOf(members);
        battles = List.copyOf(battles);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Thresholds(int minGuildPlayers, int minBattlePlayers) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Member(String playerId, String name, boolean attended, boolean inRoster,
                         List<Long> battleIds) {
        public Member { battleIds = List.copyOf(battleIds); }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Battle(long battleId, String startTimeUtc, String endTimeUtc,
                         int totalPlayers, int guildPlayers, List<String> playerIds) {
        public Battle { playerIds = List.copyOf(playerIds); }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Counts(int roster, int attended, int absent, int attendedOutsideRoster) {}
}
