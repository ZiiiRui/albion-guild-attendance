package com.albion.attendance.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GameInfoClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant START = Instant.parse("2026-10-16T00:00:00Z");
    private static final Instant END = Instant.parse("2026-10-16T12:00:00Z");
    private static CollectionSettings settings(int maxPages, int retries) {
        return new CollectionSettings("europe", "guild", "", 2, 3, 30, retries, 0, 2, maxPages);
    }
    private static JsonNode battle(long id, String stamp) { return AttendanceCollectorTest.battle(id, stamp); }
    private static ArrayNode page(JsonNode... battles) {
        ArrayNode result = MAPPER.createArrayNode();
        for (JsonNode battle : battles) result.add(battle);
        return result;
    }

    @Test void mixedBoundaryPageContinuesUntilEntirePageIsOlder() {
        List<JsonNode> pages = List.of(page(battle(5, "2026-10-16T03:00:00Z"), battle(4, "2026-10-16T01:00:00Z")),
                page(battle(3, "2026-10-16T00:00:00Z"), battle(2, "2026-10-15T23:00:00Z")),
                page(battle(1, "2026-10-15T22:00:00Z"), battle(6, "2026-10-15T21:00:00Z")));
        AtomicInteger requests = new AtomicInteger();
        List<String> urls = new ArrayList<>();
        GameInfoClient client = new GameInfoClient((uri, timeout) -> {
            urls.add(uri.toString());
            return pages.get(requests.getAndIncrement());
        });
        List<JsonNode> result = client.guildBattles(settings(10, 0), "guild", START, END);
        assertEquals(List.of(5L, 4L, 3L), result.stream().map(GameInfoClient::battleId).toList());
        assertEquals(3, requests.get());
        assertTrue(urls.get(1).contains("offset=2"));
        assertTrue(urls.getFirst().startsWith("https://gameinfo-ams.albiononline.com/"));
    }

    @Test void boundaryDoesNotStopValidationMidPage() {
        List<JsonNode> pages = List.of(page(battle(3, "2026-10-16T01:00:00Z"), battle(2, "2026-10-15T23:00:00Z")),
                page(battle(1, "2026-10-15T22:00:00Z"), battle(4, "2026-10-16T02:00:00Z")));
        AtomicInteger index = new AtomicInteger();
        GameInfoClient client = new GameInfoClient((uri, timeout) -> pages.get(index.getAndIncrement()));
        CollectionException error = assertThrows(CollectionException.class,
                () -> client.guildBattles(settings(10, 0), "guild", START, END));
        assertTrue(error.getMessage().contains("offset=2, id=4"));
    }

    @Test void duplicateAndLimitAndPageErrorsFailClosed() {
        JsonNode full = page(battle(2, "2026-10-16T02:00:00Z"), battle(1, "2026-10-16T01:00:00Z"));
        GameInfoClient repeating = new GameInfoClient((uri, timeout) -> full);
        CollectionException duplicate = assertThrows(CollectionException.class,
                () -> repeating.guildBattles(settings(10, 0), "guild", START, END));
        assertTrue(duplicate.getMessage().contains("id=2 at offset=2"));
        assertThrows(CollectionException.class, () -> repeating.guildBattles(settings(1, 0), "guild", START, END));
        GameInfoClient invalid = new GameInfoClient((uri, timeout) -> MAPPER.createObjectNode());
        assertThrows(CollectionException.class, () -> invalid.guildBattles(settings(10, 0), "guild", START, END));
        AtomicInteger requests = new AtomicInteger();
        GameInfoClient failing = new GameInfoClient((uri, timeout) -> {
            if (requests.getAndIncrement() == 0) return full;
            throw new IOException("unavailable");
        });
        CollectionException error = assertThrows(CollectionException.class,
                () -> failing.guildBattles(settings(10, 0), "guild", START, END));
        assertTrue(error.getMessage().contains("offset=2 failed"));
    }

    @Test void exactFullPageRequiresFollowingPage() {
        AtomicInteger requests = new AtomicInteger();
        GameInfoClient client = new GameInfoClient((uri, timeout) -> requests.getAndIncrement() == 0
                ? page(battle(2, "2026-10-16T02:00:00Z"), battle(1, "2026-10-16T01:00:00Z")) : page());
        assertEquals(2, client.guildBattles(settings(10, 0), "guild", START, END).size());
        assertEquals(2, requests.get());
    }

    @Test void transientNetworkAndHttpErrorsRetryButPermanentHttpDoesNot() {
        for (IOException failure : List.of(new IOException("offline"), new GameInfoClient.HttpStatusException(503))) {
            AtomicInteger requests = new AtomicInteger();
            GameInfoClient client = new GameInfoClient((uri, timeout) -> {
                if (requests.getAndIncrement() == 0) throw failure;
                return page();
            });
            assertTrue(client.guildBattles(settings(10, 1), "guild", START, END).isEmpty());
            assertEquals(2, requests.get());
        }
        AtomicInteger requests = new AtomicInteger();
        GameInfoClient missing = new GameInfoClient((uri, timeout) -> {
            requests.incrementAndGet();
            throw new GameInfoClient.HttpStatusException(404);
        });
        assertThrows(CollectionException.class, () -> missing.guildBattles(settings(10, 1), "guild", START, END));
        assertEquals(1, requests.get());
    }

    @Test void interruptedRequestRetainsInterruptFlag() {
        GameInfoClient client = new GameInfoClient((uri, timeout) -> { throw new InterruptedException("cancelled"); });
        try {
            assertThrows(CollectionException.class, () -> client.guildBattles(settings(10, 1), "guild", START, END));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test void guildSearchRequiresUniqueExactMatchAndIdNameMustAgree() {
        CollectionSettings named = new CollectionSettings("asia", "", "Exact", 1, 1, 30, 0, 0, 50, 200);
        ArrayNode guilds = MAPPER.createArrayNode();
        guilds.addObject().put("Id", "id").put("Name", "Exact");
        guilds.addObject().put("Id", "other").put("Name", "Exactish");
        JsonNode response = MAPPER.createObjectNode().set("guilds", guilds);
        GameInfoClient client = new GameInfoClient((uri, timeout) -> response);
        assertEquals(new Guild("id", "Exact"), client.resolveGuild(named));
        guilds.addObject().put("Id", "duplicate").put("Name", "EXACT");
        assertThrows(CollectionException.class, () -> client.resolveGuild(named));
        GameInfoClient wrong = new GameInfoClient((uri, timeout) -> MAPPER.createObjectNode().put("Id", "guild").put("Name", "Actual"));
        CollectionSettings mismatch = new CollectionSettings("asia", "guild", "Wrong", 1, 1, 30, 0, 0, 50, 200);
        assertThrows(CollectionException.class, () -> wrong.resolveGuild(mismatch));
    }

    @Test void settingsRejectUnsupportedServerAndInvalidLimits() {
        assertThrows(IllegalArgumentException.class, () -> new CollectionSettings("us", "guild", "", 1, 1, 30, 0, 0, 50, 200));
        assertThrows(IllegalArgumentException.class, () -> new CollectionSettings("asia", "", "", 1, 1, 30, 0, 0, 50, 200));
        assertThrows(IllegalArgumentException.class, () -> new CollectionSettings("asia", "guild", "", 1, 1, 30, 0, 0, 51, 200));
        assertThrows(IllegalArgumentException.class, () -> new CollectionSettings("asia", "guild", "", -1, 1, 30, 0, 0, 50, 200));
    }
}
