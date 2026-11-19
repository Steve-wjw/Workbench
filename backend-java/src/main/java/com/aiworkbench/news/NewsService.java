package com.aiworkbench.news;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.aiworkbench.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Reads AIHOT editorial picks on demand. Nothing is persisted outside this process. */
@Service
public class NewsService {
    private static final Logger LOG = LoggerFactory.getLogger(NewsService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration MIN_REFRESH_INTERVAL = Duration.ofMinutes(1);
    private static final int MAX_PAGES = 3;
    private static final int DAILY_LIMIT = 5;

    private final RestClient client;
    private final Clock clock;
    private Snapshot snapshot;
    private Instant nextAttemptAt = Instant.MIN;

    @Autowired
    public NewsService(RestClient.Builder builder) {
        this(builder.requestFactory(requestFactory()).baseUrl("https://aihot.news").build(), Clock.systemUTC());
    }

    NewsService(RestClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    private static JdkClientHttpRequestFactory requestFactory() {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(4));
        return factory;
    }

    // Serialize refreshes so repeated navigation and concurrent requests share one upstream fetch.
    public synchronized NewsModels.NewsResponse list(boolean refresh) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZONE);
        boolean sameDay = snapshot != null && snapshot.date().equals(today);
        if (sameDay && ((!refresh && snapshot.fetchedAt().plus(TTL).isAfter(now))
                || nextAttemptAt.isAfter(now))) {
            return response(snapshot, true, snapshot.warning());
        }
        if (nextAttemptAt.isAfter(now)) {
            throw unavailable();
        }

        nextAttemptAt = now.plus(MIN_REFRESH_INTERVAL);
        try {
            Snapshot loaded = load(today, now);
            snapshot = loaded;
            return response(loaded, false, loaded.warning());
        } catch (RuntimeException exception) {
            if (exception instanceof RestClientResponseException upstream
                    && (upstream.getStatusCode().value() == 429 || upstream.getStatusCode().value() == 503)) {
                nextAttemptAt = retryAt(upstream, now);
            }
            LOG.warn("AI news query failed: {}", exception.getClass().getSimpleName());
            if (sameDay) {
                String warning = "热点更新失败，当前显示上次获取的内容，请稍后刷新。";
                snapshot = new Snapshot(snapshot.date(), snapshot.items(), snapshot.fetchedAt(), snapshot.etag(), warning);
                return response(snapshot, true, warning);
            }
            throw unavailable();
        }
    }

    private Snapshot load(LocalDate today, Instant now) {
        List<NewsModels.NewsItem> items = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        Set<String> seenTitles = new HashSet<>();
        String cursor = null;
        String etag = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            final String currentCursor = cursor;
            var request = client.get().uri(builder -> {
                builder.path("/api/v1/items").queryParam("mode", "selected")
                        .queryParam("window", "7d").queryParam("by", "published").queryParam("limit", 100);
                if (currentCursor != null) builder.queryParam("cursor", currentCursor);
                return builder.build();
            }).header("User-Agent", "AI-Workbench/1.0.0 (personal local reader)");
            if (page == 0 && snapshot != null && snapshot.etag() != null) {
                request.header("If-None-Match", snapshot.etag());
            }
            var upstream = request.retrieve().toEntity(JsonNode.class);
            if (upstream.getStatusCode().value() == 304 && snapshot != null) {
                return new Snapshot(today, snapshot.items(), now, snapshot.etag(), null);
            }
            JsonNode body = upstream.getBody();
            if (body == null || !body.path("items").isArray()) {
                throw new IllegalStateException("Invalid AIHOT items response");
            }
            etag = page == 0 ? upstream.getHeaders().getETag() : null;
            boolean reachedOlder = false;
            for (JsonNode node : body.path("items")) {
                NewsModels.NewsItem item = item(node);
                if (item == null) continue;
                LocalDate date = item.publishedAt().atZoneSameInstant(ZONE).toLocalDate();
                if (date.isBefore(today.minusDays(1))) reachedOlder = true;
                if (date.isAfter(today) || date.isBefore(today.minusDays(1))) continue;
                String key = item.title().replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
                if (seenUrls.contains(item.url()) || seenTitles.contains(key)) continue;
                seenUrls.add(item.url());
                seenTitles.add(key);
                items.add(item);
            }
            cursor = body.path("page").path("nextCursor").asText("");
            if (reachedOlder || !body.path("page").path("hasMore").asBoolean() || cursor.isBlank()) {
                return new Snapshot(today, List.copyOf(items), now, etag, null);
            }
        }
        return new Snapshot(today, List.copyOf(items), now, null, "新闻较多，当前展示已获取的精选热点。");
    }

    private NewsModels.NewsItem item(JsonNode node) {
        String title = node.path("title").asText("").strip();
        String url = safeUrl(node.path("links").path("original").asText(""));
        String providerUrl = safeUrl(node.path("links").path("aihot").asText(""));
        if (title.isBlank() || url.isBlank() || node.path("publishedAt").isNull()) return null;
        try {
            OffsetDateTime publishedAt = OffsetDateTime.parse(node.path("publishedAt").asText(""));
            String summary = node.path("summary").asText("").strip();
            return new NewsModels.NewsItem(
                    node.path("id").asText(url), title, node.path("source").path("name").asText("未知来源"),
                    url, publishedAt, summary.isBlank() ? "该新闻暂未提供概述，可查看原文。" : summary,
                    category(node.path("category").asText("")), providerUrl,
                    node.path("reason").asText(""), hotScore(node));
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private String safeUrl(String url) {
        try {
            URI uri = URI.create(url);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null ? url : "";
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    private String category(String value) {
        return switch (value) {
            case "ai-models" -> "模型";
            case "ai-products" -> "产品";
            case "industry" -> "行业动态";
            case "paper" -> "研究";
            case "tip" -> "实践";
            case "opinion" -> "观点";
            default -> "AI 动态";
        };
    }

    private Double hotScore(JsonNode node) {
        JsonNode score = node.path("score");
        if (score.isObject()) score = score.path("total");
        if (!score.isNumber()) score = node.path("scores").path("total");
        return score.isNumber() && Double.isFinite(score.asDouble()) ? score.asDouble() : null;
    }

    private NewsModels.NewsResponse response(Snapshot value, boolean cached, String warning) {
        var today = day(value, value.date(), "今天");
        var yesterday = day(value, value.date().minusDays(1), "昨天");
        if (yesterday.items().stream().anyMatch(item -> item.hotScore() == null)) {
            String missingScore = "部分昨日新闻未提供热度评分，已按发布时间补充。";
            warning = warning == null ? missingScore : warning + missingScore;
        }
        return new NewsModels.NewsResponse(today, yesterday,
                value.fetchedAt().atZone(ZONE).toOffsetDateTime(), cached, warning);
    }

    private NewsModels.NewsDay day(Snapshot value, LocalDate date, String label) {
        Comparator<NewsModels.NewsItem> newest = Comparator.comparing(
                NewsModels.NewsItem::publishedAt).reversed();
        Comparator<NewsModels.NewsItem> order = date.equals(value.date()) ? newest
                : Comparator.comparing(NewsModels.NewsItem::hotScore,
                        Comparator.nullsLast(Comparator.<Double>reverseOrder())).thenComparing(newest);
        return new NewsModels.NewsDay(date, label, value.items().stream()
                .filter(item -> item.publishedAt().atZoneSameInstant(ZONE).toLocalDate().equals(date))
                .sorted(order)
                .limit(DAILY_LIMIT).toList());
    }

    private Instant retryAt(RestClientResponseException exception, Instant now) {
        String value = exception.getResponseHeaders() == null ? null
                : exception.getResponseHeaders().getFirst("Retry-After");
        try {
            long seconds = Long.parseLong(value);
            return now.plusSeconds(Math.max(60, seconds));
        } catch (RuntimeException ignored) {
            try {
                Instant retry = java.time.ZonedDateTime.parse(value,
                        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return retry.isAfter(now.plusSeconds(60)) ? retry : now.plusSeconds(60);
            } catch (RuntimeException invalidDate) {
                return now.plusSeconds(60);
            }
        }
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "AI_NEWS_UNAVAILABLE",
                "暂时无法获取 AI 热点，请稍后重试，或前往 AIHOT 查看。");
    }

    private record Snapshot(LocalDate date, List<NewsModels.NewsItem> items, Instant fetchedAt,
            String etag, String warning) {
    }
}
