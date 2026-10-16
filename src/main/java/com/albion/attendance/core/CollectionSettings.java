package com.albion.attendance.core;

import java.util.Locale;
import java.util.Set;

public record CollectionSettings(String server, String guildId, String guildName,
                                 int minGuildPlayers, int minBattlePlayers,
                                 int timeoutSeconds, int retries, int retrySeconds,
                                 int pageLimit, int maxPages) {
    public CollectionSettings {
        server = server == null ? "" : server.trim().toLowerCase(Locale.ROOT);
        guildId = guildId == null ? "" : guildId.trim();
        guildName = guildName == null ? "" : guildName.trim();
        if (!Set.of("asia", "europe", "americas").contains(server))
            throw new IllegalArgumentException("server must be asia, europe, or americas");
        if (guildId.isBlank() && guildName.isBlank())
            throw new IllegalArgumentException("configure guildId or guildName");
        range(minGuildPlayers, "minGuildPlayers", 0, 1_000_000);
        range(minBattlePlayers, "minBattlePlayers", 0, 1_000_000);
        range(timeoutSeconds, "timeoutSeconds", 1, 300);
        range(retries, "retries", 0, 10);
        range(retrySeconds, "retrySeconds", 0, 300);
        range(pageLimit, "pageLimit", 1, 50);
        range(maxPages, "maxPages", 1, 10_000);
    }

    private static void range(int value, String name, int minimum, int maximum) {
        if (value < minimum || value > maximum)
            throw new IllegalArgumentException(name + " must be in " + minimum + ".." + maximum);
    }
}
