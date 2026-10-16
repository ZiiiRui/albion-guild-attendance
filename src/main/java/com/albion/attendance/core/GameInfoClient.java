package com.albion.attendance.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GameInfoClient {
    private static final Logger LOG = LoggerFactory.getLogger(GameInfoClient.class);
    private static final Map<String, String> SERVERS = Map.of(
            "asia", "https://gameinfo-sgp.albiononline.com/api/gameinfo",
            "europe", "https://gameinfo-ams.albiononline.com/api/gameinfo",
            "americas", "https://gameinfo.albiononline.com/api/gameinfo");
    private static final Set<Integer> RETRY_STATUS = Set.of(408, 429, 500, 502, 503, 504);
    private final Transport transport;

    @FunctionalInterface
    public interface Transport {
        JsonNode get(URI uri, Duration timeout) throws IOException, InterruptedException;
    }

    public static class HttpStatusException extends IOException {
        private final int status;
        public HttpStatusException(int status) { super("GameInfo HTTP " + status); this.status = status; }
        public int status() { return status; }
    }

    public GameInfoClient() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        ObjectMapper mapper = new ObjectMapper();
        this.transport = (uri, timeout) -> {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("User-Agent", "albion-guild-attendance/2.0")
                    .header("Cache-Control", "no-cache").header("Accept", "application/json").GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new HttpStatusException(response.statusCode());
            try {
                JsonNode data = mapper.readTree(response.body());
                if (data == null) throw new CollectionException("GameInfo returned an empty response");
                return data;
            } catch (JsonProcessingException exc) {
                throw new CollectionException("GameInfo returned invalid JSON", exc);
            }
        };
    }

    public GameInfoClient(Transport transport) { this.transport = transport; }

    private JsonNode get(CollectionSettings settings, String path) {
        IOException failure = null;
        URI uri = URI.create(SERVERS.get(settings.server()) + path);
        for (int attempt = 0; attempt <= settings.retries(); attempt++) {
            try {
                JsonNode response = transport.get(uri, Duration.ofSeconds(settings.timeoutSeconds()));
                if (response == null || response.isNull()) throw new CollectionException("GameInfo returned null: " + path);
                return response;
            } catch (HttpStatusException exc) {
                if (!RETRY_STATUS.contains(exc.status())) throw new CollectionException(exc.getMessage() + ": " + path, exc);
                failure = exc;
            } catch (IOException exc) {
                failure = exc;
            } catch (InterruptedException exc) {
                Thread.currentThread().interrupt();
                throw new CollectionException("GameInfo request interrupted", exc);
            }
            if (attempt < settings.retries()) {
                try {
                    Thread.sleep(Duration.ofSeconds((long) settings.retrySeconds() * (1L << attempt)));
                } catch (InterruptedException exc) {
                    Thread.currentThread().interrupt();
                    throw new CollectionException("GameInfo retry interrupted", exc);
                }
            }
        }
        throw new CollectionException("GameInfo request failed after retries: " + path, failure);
    }

    public Guild resolveGuild(CollectionSettings settings) {
        if (!settings.guildId().isBlank()) {
            JsonNode guild = get(settings, "/guilds/" + encode(settings.guildId()));
            String id = text(guild.get("Id"), "guild id");
            String name = text(guild.get("Name"), "guild name");
            if (!settings.guildId().equals(id)) throw new CollectionException("guild lookup returned the wrong id");
            if (!settings.guildName().isBlank() && !name.equalsIgnoreCase(settings.guildName()))
                throw new CollectionException("configured guild id and name refer to different guilds");
            return new Guild(id, name);
        }
        JsonNode guilds = get(settings, "/search?q=" + encode(settings.guildName())).get("guilds");
        if (guilds == null || !guilds.isArray()) throw new CollectionException("invalid guild search response");
        List<Guild> matches = new ArrayList<>();
        for (JsonNode guild : guilds) {
            if (guild.path("Name").isTextual() && guild.path("Name").asText().equalsIgnoreCase(settings.guildName()))
                matches.add(new Guild(text(guild.get("Id"), "guild id"), text(guild.get("Name"), "guild name")));
        }
        if (matches.size() != 1) throw new CollectionException("guild name must match exactly one guild; configure guild id");
        return matches.getFirst();
    }

    public JsonNode guildMembers(CollectionSettings settings, String guildId) {
        JsonNode members = get(settings, "/guilds/" + encode(guildId) + "/members");
        if (!members.isArray()) throw new CollectionException("guild members response is not a complete list");
        return members;
    }

    public List<JsonNode> guildBattles(CollectionSettings settings, String guildId, Instant start, Instant end) {
        List<JsonNode> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Instant previous = null;
        int offset = 0;
        for (int pageNumber = 0; pageNumber < settings.maxPages(); pageNumber++) {
            offset = pageNumber * settings.pageLimit();
            JsonNode page;
            try {
                page = get(settings, "/battles?limit=" + settings.pageLimit() + "&offset=" + offset
                        + "&range=week&sort=recent&guildId=" + encode(guildId));
            } catch (CollectionException exc) {
                throw new CollectionException("battle page offset=" + offset + " failed: " + exc.getMessage(), exc);
            }
            if (!page.isArray() || page.size() > settings.pageLimit())
                throw new CollectionException("invalid battles page at offset=" + offset);
            int olderCount = 0;
            Instant oldest = null;
            for (JsonNode battle : page) {
                long id;
                Instant stamp;
                try {
                    id = battleId(battle);
                    stamp = timestamp(battle.get("startTime"));
                } catch (CollectionException exc) {
                    throw new CollectionException("invalid battle at offset=" + offset + ": " + exc.getMessage(), exc);
                }
                if (!seen.add(id)) throw new CollectionException("battle pagination repeated id=" + id + " at offset=" + offset);
                if (previous != null && stamp.isAfter(previous))
                    throw new CollectionException("battle pagination is not newest-first at offset=" + offset + ", id=" + id);
                previous = stamp;
                oldest = stamp;
                if (stamp.isBefore(start)) olderCount++;
                if (!stamp.isBefore(start) && stamp.isBefore(end)) result.add(battle);
            }
            LOG.info("battle page offset={} count={} oldest={}", offset, page.size(), oldest);
            if (page.size() < settings.pageLimit() || olderCount == page.size()) return result;
        }
        throw new CollectionException("battle pagination limit reached at offset=" + offset + "; refusing an incomplete report");
    }

    static String text(JsonNode value, String label) {
        if (value == null || !value.isTextual() || value.asText().isBlank())
            throw new CollectionException(label + " is missing");
        return value.asText();
    }

    static long battleId(JsonNode battle) {
        JsonNode id = battle.get("id");
        if (id == null || !id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0)
            throw new CollectionException("invalid battle id");
        return id.longValue();
    }

    static Instant timestamp(JsonNode value) {
        String text = text(value, "battle timestamp");
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException exc) {
            throw new CollectionException("invalid battle timestamp: " + text, exc);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
