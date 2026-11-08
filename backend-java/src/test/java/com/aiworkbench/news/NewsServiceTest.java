package com.aiworkbench.news;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.aiworkbench.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** Deterministic API tests: no network, PostgreSQL, model credentials or local data writes. */
class NewsServiceTest {
    private final MutableClock clock = new MutableClock();
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://aihot.news");
    private final MockRestServiceServer upstream = MockRestServiceServer.bindTo(builder).build();
    private final NewsService service = new NewsService(builder.build(), clock);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void groupsByShanghaiDateAndPreservesFullSummary() throws Exception {
        var current = article("current", "2026-10-31T17:00:00Z");
        String summary = "Long overview ".repeat(100);
        current.put("summary", summary);
        expectFeed(List.of(current, article("yesterday", "2026-10-30T17:00:00Z"),
                article("old", "2026-10-29T17:00:00Z"), article("future", "2026-11-02T01:00:00Z")), false);

        var response = service.list(false);

        assertEquals("2026-11-01", response.today().date().toString());
        assertEquals(List.of("current"), response.today().items().stream().map(NewsModels.NewsItem::id).toList());
        assertEquals(List.of("yesterday"), response.yesterday().items().stream().map(NewsModels.NewsItem::id).toList());
        assertEquals(summary.strip(), response.today().items().getFirst().summary());
        assertEquals("模型", response.today().items().getFirst().category());
        upstream.verify();
    }

    @Test
    void skipsInvalidDatesUnsafeUrlsAndDuplicateTitlesOrUrls() throws Exception {
        var duplicateUrl = article("second", "2026-11-01T01:00:00Z");
        duplicateUrl.put("links", Map.of("original", "https://example.com/first"));
        var duplicateTitle = article("third", "2026-11-01T01:00:00Z");
        duplicateTitle.put("title", "first");
        var unsafe = article("unsafe", "2026-11-01T01:00:00Z");
        unsafe.put("links", Map.of("original", "javascript:alert(1)", "aihot", "data:text/plain,test"));
        expectFeed(List.of(article("first", "2026-11-01T00:00:00Z"), duplicateUrl, duplicateTitle,
                unsafe, article("invalid-date", "yesterday")), false);

        var items = service.list(false).today().items();
        assertEquals(1, items.size());
        assertEquals("first", items.getFirst().id());
        upstream.verify();
    }

    @Test
    void todayShowsAtMostFiveNewestArticlesRegardlessOfScore() throws Exception {
        var articles = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 15; i++) {
            var item = article("item-" + i, "2026-11-01T%02d:00:00Z".formatted(i));
            item.put("score", 100 - i);
            articles.add(item);
        }
        expectFeed(articles, false);
        var response = service.list(false);
        assertEquals(5, response.today().items().size());
        assertEquals("item-14", response.today().items().getFirst().id());
        upstream.verify();
    }

    @Test
    void yesterdayShowsTheFiveHighestScoresNotTheFiveNewestArticles() throws Exception {
        var articles = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 8; i++) {
            var item = article("item-" + i, "2026-10-31T%02d:00:00Z".formatted(i));
            item.put("score", 100 - i);
            articles.add(item);
        }
        expectFeed(articles, false);
        var items = service.list(false).yesterday().items();
        assertEquals(List.of("item-0", "item-1", "item-2", "item-3", "item-4"),
                items.stream().map(NewsModels.NewsItem::id).toList());
        upstream.verify();
    }

    @Test
    void yesterdayBreaksScoreTiesByTimeAndWarnsWhenScoresAreMissing() throws Exception {
        var earlier = article("earlier", "2026-10-31T00:00:00Z");
        earlier.put("score", Map.of("total", 80));
        var later = article("later", "2026-10-31T01:00:00Z");
        later.remove("score");
        later.put("scores", Map.of("total", 80));
        var unscored = article("unscored", "2026-10-31T02:00:00Z");
        unscored.remove("score");
        expectFeed(List.of(unscored, earlier, later), false);
        var response = service.list(false);
        assertEquals(List.of("later", "earlier", "unscored"),
                response.yesterday().items().stream().map(NewsModels.NewsItem::id).toList());
        assertNotNull(response.warning());
        upstream.verify();
    }

    @Test
    void cachesRepeatedNavigationAndThrottlesImmediateRefresh() throws Exception {
        expectFeed(List.of(article("first", "2026-11-01T00:00:00Z")), false);
        assertFalse(service.list(false).cached());
        assertTrue(service.list(false).cached());
        assertTrue(service.list(true).cached());
        upstream.verify();
    }

    @Test
    void forceRefreshAndExpiryFetchAgain() throws Exception {
        expectFeed(List.of(article("first", "2026-11-01T00:00:00Z")), false);
        expectFeed(List.of(article("second", "2026-11-01T00:00:00Z")), false);
        expectFeed(List.of(), false);
        service.list(false);
        clock.advance(Duration.ofMinutes(1));
        assertEquals("second", service.list(true).today().items().getFirst().id());
        clock.advance(Duration.ofMinutes(10));
        assertTrue(service.list(false).today().items().isEmpty());
        upstream.verify();
    }

    @Test
    void handlesConditionalRevalidation() throws Exception {
        upstream.expect(requestTo(org.hamcrest.Matchers.containsString("/api/v1/items?")))
                .andRespond(withSuccess(feed(List.of(article("first", "2026-11-01T00:00:00Z")), false),
                        MediaType.APPLICATION_JSON).header(HttpHeaders.ETAG, "\"v1\""));
        upstream.expect(header(HttpHeaders.IF_NONE_MATCH, "\"v1\""))
                .andRespond(withStatus(HttpStatus.NOT_MODIFIED));
        service.list(false);
        clock.advance(Duration.ofMinutes(11));
        var response = service.list(false);
        assertEquals(1, response.today().items().size());
        assertNull(response.warning());
        upstream.verify();
    }

    @Test
    void followsCursorForYesterdaysArticles() throws Exception {
        expectFeed(List.of(article("today", "2026-11-01T00:00:00Z")), true);
        upstream.expect(requestTo(org.hamcrest.Matchers.containsString("cursor=next-page")))
                .andRespond(withSuccess(feed(List.of(article("yesterday", "2026-10-31T00:00:00Z")), false),
                        MediaType.APPLICATION_JSON));
        var response = service.list(false);
        assertEquals(1, response.today().items().size());
        assertEquals(1, response.yesterday().items().size());
        upstream.verify();
    }

    @Test
    void stopsAfterThreePagesAndWarnsThatResultsArePartial() throws Exception {
        expectFeed(List.of(article("first", "2026-11-01T00:00:00Z")), true);
        expectFeed(List.of(article("second", "2026-11-01T00:00:00Z")), true);
        expectFeed(List.of(article("third", "2026-11-01T00:00:00Z")), true);
        var response = service.list(false);
        assertEquals(3, response.today().items().size());
        assertNotNull(response.warning());
        upstream.verify();
    }

    @Test
    void fallsBackToSameDayCacheWithAnExplicitWarning() throws Exception {
        expectFeed(List.of(article("first", "2026-11-01T00:00:00Z")), false);
        upstream.expect(anything()).andRespond(withServerError());
        service.list(false);
        clock.advance(Duration.ofMinutes(11));
        var response = service.list(false);
        assertTrue(response.cached());
        assertNotNull(response.warning());
        assertEquals("2026-11-01T00:30:00Z", response.fetchedAt().toInstant().toString());
        upstream.verify();
    }

    @Test
    void neverRelabelsPreviousDaysCacheAsTodayOnFailure() throws Exception {
        expectFeed(List.of(article("first", "2026-11-01T00:00:00Z")), false);
        upstream.expect(anything()).andRespond(withServerError());
        service.list(false);
        clock.advance(Duration.ofDays(1));
        assertThrows(ApiException.class, () -> service.list(false));
        upstream.verify();
    }

    @Test
    void honorsUpstreamRetryAfterAndDoesNotCacheFailuresAsEmptyNews() throws Exception {
        upstream.expect(anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "120"));
        expectFeed(List.of(article("first", "2026-11-01T00:00:00Z")), false);
        assertThrows(ApiException.class, () -> service.list(false));
        clock.advance(Duration.ofSeconds(70));
        assertThrows(ApiException.class, () -> service.list(true));
        clock.advance(Duration.ofSeconds(60));
        assertEquals(1, service.list(false).today().items().size());
        upstream.verify();
    }

    @Test
    void invalidPayloadIsAnErrorButAnEmptyFeedIsNot() throws Exception {
        upstream.expect(anything()).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        expectFeed(List.of(), false);
        assertThrows(ApiException.class, () -> service.list(false));
        clock.advance(Duration.ofMinutes(1));
        assertTrue(service.list(false).today().items().isEmpty());
        upstream.verify();
    }

    @Test
    void controllerReturnsJsonAndNoStoreHeader() throws Exception {
        expectFeed(List.of(article("first", "2026-11-01T00:00:00Z")), false);
        standaloneSetup(new NewsController(service)).build()
                .perform(get("/api/v1/ai-news").param("refresh", "true"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.today.items[0].title").value("first"));
        upstream.verify();
    }

    private void expectFeed(List<Map<String, Object>> articles, boolean more) throws Exception {
        upstream.expect(requestTo(org.hamcrest.Matchers.containsString("/api/v1/items?")))
                .andRespond(withSuccess(feed(articles, more), MediaType.APPLICATION_JSON));
    }

    private String feed(List<Map<String, Object>> articles, boolean more) throws Exception {
        return mapper.writeValueAsString(Map.of("items", articles,
                "page", Map.of("hasMore", more, "nextCursor", more ? "next-page" : "")));
    }

    private Map<String, Object> article(String id, String publishedAt) {
        return new java.util.LinkedHashMap<>(Map.of(
                "id", id, "title", id, "source", Map.of("name", "Example"),
                "publishedAt", publishedAt, "summary", "Overview for " + id,
                "category", "ai-models", "score", 50,
                "links", Map.of("original", "https://example.com/" + id,
                        "aihot", "https://aihot.news/item/" + id)));
    }

    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-01T00:30:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
