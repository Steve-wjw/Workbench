package com.aiworkbench.news;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

import com.aiworkbench.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

/** Reads AIHOT editorial picks on demand. Nothing is persisted outside this process. */
@Service
public class NewsService {
    private static final Logger LOG = LoggerFactory.getLogger(NewsService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration MIN_REFRESH_INTERVAL = Duration.ofMinutes(1);
    private static final Duration FAILURE_COOLDOWN = Duration.ofSeconds(15);
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_PAGES = 3;
    private static final int DAILY_LIMIT = 5;
    private static final int MAX_ATTEMPTS = 3;

    private final RestClient client;
    private final Clock clock;
    private final Duration queryTimeout;
    private final Sleeper sleeper;
    private final ReentrantLock refreshLock = new ReentrantLock();
    private Snapshot snapshot;
    private Instant nextAttemptAt = Instant.MIN;

    @Autowired
    public NewsService(RestClient.Builder builder) {
        this(builder.requestFactory(requestFactory()).baseUrl("https://aihot.news").build(), Clock.systemUTC());
    }

    NewsService(RestClient client, Clock clock) {
        this(client, clock, QUERY_TIMEOUT, Thread::sleep);
    }

    NewsService(RestClient client, Clock clock, Duration queryTimeout, Sleeper sleeper) {
        this.client = client;
        this.clock = clock;
        this.queryTimeout = queryTimeout;
        this.sleeper = sleeper;
    }

    private static JdkClientHttpRequestFactory requestFactory() {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        return factory;
    }

    // Include lock contention in the same deadline as pagination and retries.
    public NewsModels.NewsResponse list(boolean refresh) {
        long started = System.nanoTime();
        try {
            if (!refreshLock.tryLock(queryTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw unavailable();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
        try {
            return listLocked(refresh, started);
        } finally {
            refreshLock.unlock();
        }
    }

    private NewsModels.NewsResponse listLocked(boolean refresh, long started) {
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

        try {
            Snapshot loaded = loadWithinDeadline(today, now, started);
            snapshot = loaded;
            nextAttemptAt = clock.instant().plus(MIN_REFRESH_INTERVAL);
            return response(loaded, false, loaded.warning());
        } catch (RuntimeException exception) {
            Instant failedAt = clock.instant();
            nextAttemptAt = failedAt.plus(retryable(exception) ? FAILURE_COOLDOWN : MIN_REFRESH_INTERVAL);
            if (exception instanceof RestClientResponseException upstream
                    && (upstream.getStatusCode().value() == 429
                    || (upstream.getStatusCode().value() == 503 && upstream.getResponseHeaders() != null
                    && upstream.getResponseHeaders().containsKey("Retry-After")))) {
                nextAttemptAt = retryAt(upstream, failedAt);
            }
            LOG.warn("AI news query failed: type={} cause={} elapsed_ms={} cooldown_ms={}",
                    exception.getClass().getSimpleName(), rootCauseType(exception), elapsedMillis(started),
                    Duration.between(failedAt, nextAttemptAt).toMillis());
            if (sameDay) {
                String warning = "热点更新失败，当前显示上次获取的内容，请稍后刷新。";
                snapshot = new Snapshot(snapshot.date(), snapshot.items(), snapshot.fetchedAt(), snapshot.etag(), warning);
                return response(snapshot, true, warning);
            }
            throw unavailable();
        }
    }

    private Snapshot loadWithinDeadline(LocalDate today, Instant now, long started) {
        long remaining = queryTimeout.toNanos() - (System.nanoTime() - started);
        if (remaining <= 0) throw deadlineExceeded();
        Snapshot previous = snapshot;
        var context = MDC.getCopyOfContextMap();
        // Cancellation also covers a slow response body or parsing, not only response headers.
        var task = new FutureTask<>(() -> {
            if (context != null) MDC.setContextMap(context);
            try {
                return load(today, now, previous, started);
            } finally {
                MDC.clear();
            }
        });
        Thread.ofVirtual().name("ai-news-fetch").start(task);
        try {
            remaining = Math.max(0, queryTimeout.toNanos() - (System.nanoTime() - started));
            return task.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            throw deadlineExceeded();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CancellationException("AI news query interrupted");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException failure) throw failure;
            if (exception.getCause() instanceof Error failure) throw failure;
            throw new IllegalStateException("AI news query failed", exception.getCause());
        } finally {
            task.cancel(true);
        }
    }

    private Snapshot load(LocalDate today, Instant now, Snapshot previous, long started) {
        List<NewsModels.NewsItem> items = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        Set<String> seenTitles = new HashSet<>();
        String cursor = null;
        String etag = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            String previousEtag = page == 0 && previous != null ? previous.etag() : null;
            var upstream = fetchPage(cursor, previousEtag, page, started);
            if (upstream.getStatusCode().value() == 304 && previous != null) {
                return new Snapshot(today, previous.items(), now, previous.etag(), null);
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

    private ResponseEntity<JsonNode> fetchPage(String cursor, String etag, int page, long started) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("AI news query interrupted");
            }
            if (System.nanoTime() - started >= queryTimeout.toNanos()) throw deadlineExceeded();
            try {
                var request = client.get().uri(builder -> {
                    builder.path("/api/v1/items").queryParam("mode", "selected")
                            .queryParam("window", "7d").queryParam("by", "published").queryParam("limit", 100);
                    if (cursor != null) builder.queryParam("cursor", cursor);
                    return builder.build();
                }).header("User-Agent", "AI-Workbench/1.0.0 (personal local reader)");
                if (etag != null) request.header("If-None-Match", etag);
                var result = request.retrieve().toEntity(JsonNode.class);
                if (attempt > 1) {
                    LOG.info("AI news page recovered: page={} attempt={} elapsed_ms={}",
                            page + 1, attempt, elapsedMillis(started));
                }
                return result;
            } catch (RuntimeException exception) {
                boolean retry = attempt < MAX_ATTEMPTS && retryable(exception);
                long delay = retry ? (500L << (attempt - 1)) + ThreadLocalRandom.current().nextLong(151) : 0;
                // Log only types/statuses, never upstream bodies, URLs or exception messages.
                LOG.warn("AI news page failed: page={} attempt={} type={} cause={} status={} elapsed_ms={} retry_ms={}",
                        page + 1, attempt, exception.getClass().getSimpleName(), rootCauseType(exception),
                        exception instanceof RestClientResponseException upstream ? upstream.getStatusCode().value() : null,
                        elapsedMillis(started), delay);
                if (!retry) throw exception;
                try {
                    sleeper.sleep(Duration.ofMillis(delay));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("AI news retry interrupted");
                }
            }
        }
        throw new IllegalStateException("AI news attempts exhausted");
    }

    private boolean retryable(RuntimeException exception) {
        if (Thread.currentThread().isInterrupted()) return false;
        if (exception instanceof ResourceAccessException) {
            Throwable cause = exception;
            for (int depth = 0; cause != null && depth < 10; depth++, cause = cause.getCause()) {
                if (cause instanceof SSLHandshakeException || cause instanceof SSLPeerUnverifiedException) return false;
            }
            return true;
        }
        if (exception instanceof RestClientResponseException upstream) {
            int status = upstream.getStatusCode().value();
            boolean retryAfter = upstream.getResponseHeaders() != null
                    && upstream.getResponseHeaders().containsKey("Retry-After");
            return status == 502 || status == 504 || (status == 503 && !retryAfter);
        }
        return false;
    }

    private String rootCauseType(Throwable exception) {
        Throwable cause = exception;
        for (int depth = 0; cause.getCause() != null && depth < 10; depth++) cause = cause.getCause();
        return cause.getClass().getSimpleName();
    }

    private long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private ResourceAccessException deadlineExceeded() {
        return new ResourceAccessException("AI news query deadline exceeded",
                new HttpTimeoutException("AI news query exceeded its total time budget"));
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

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}
