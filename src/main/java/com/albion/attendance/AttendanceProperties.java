package com.albion.attendance;

import com.albion.attendance.core.CollectionSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("attendance")
public record AttendanceProperties(
        @Pattern(regexp = "asia|europe|americas") @NotNull String server,
        String guildId, String guildName,
        @Pattern(regexp = "(?:[01][0-9]|2[0-3]):[0-5][0-9]") @NotNull String fetchTimeUtc,
        @Min(0) @Max(1000000) int minGuildPlayers,
        @Min(0) @Max(1000000) int minBattlePlayers,
        @NotBlank String outputDirectory, boolean schedulingEnabled, boolean finalizePreviousDay,
        @Min(1) @Max(86400) int retryDelaySeconds, @Valid @NotNull Api api) {

    public AttendanceProperties {
        guildId = guildId == null ? "" : guildId.trim();
        guildName = guildName == null ? "" : guildName.trim();
    }

    @AssertTrue(message = "configure attendance.guild-id or attendance.guild-name")
    public boolean isGuildConfigured() {
        return !guildId.isBlank() || !guildName.isBlank();
    }

    public CollectionSettings collectionSettings() {
        return new CollectionSettings(server, guildId, guildName, minGuildPlayers, minBattlePlayers,
                api.timeoutSeconds, api.retries, api.retrySeconds, api.pageLimit, api.maxPages);
    }

    public record Api(@Min(1) @Max(300) int timeoutSeconds, @Min(0) @Max(10) int retries,
                      @Min(0) @Max(300) int retrySeconds, @Min(1) @Max(50) int pageLimit,
                      @Min(1) @Max(10000) int maxPages) { }
}
