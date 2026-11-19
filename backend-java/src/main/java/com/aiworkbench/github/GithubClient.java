package com.aiworkbench.github;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.aiworkbench.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class GithubClient {
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private final RestClient client;
    private final Clock clock;
    private final String token;
    private final Map<String, Snapshot> cache = new LinkedHashMap<>();
    private Instant blockedUntil = Instant.MIN;

    public record Snapshot(GithubModels.Metadata metadata, String etag, OffsetDateTime fetchedAt) {
    }

    @Autowired
    public GithubClient(RestClient.Builder builder, @Value("${workbench.github.token:}") String token) {
        this(builder.requestFactory(requestFactory()).build(), token, Clock.systemUTC());
    }

    GithubClient(RestClient client, String token, Clock clock) {
        this.client = client;
        this.token = token;
        this.clock = clock;
    }

    private static JdkClientHttpRequestFactory requestFactory() {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        return factory;
    }

    // 串行化上游请求，合并“预览 -> 保存”并限制短时间重复同步。
    public synchronized Snapshot fetch(GithubAddress address, String etag, GithubModels.Metadata previous, boolean force) {
        Instant now = clock.instant();
        Snapshot cached = cache.get(address.fullName());
        if (cached != null && cached.fetchedAt().toInstant().plus(force ? Duration.ofSeconds(10) : CACHE_TTL).isAfter(now)) {
            return cached;
        }
        if (blockedUntil.isAfter(now)) throw unavailable("GitHub 请求额度暂不可用，请稍后同步。");
        try {
            URI uri = URI.create("https://api.github.com/repos/" + address.fullName());
            for (int redirects = 0; redirects <= 3; redirects++) {
                var request = client.get().uri(uri)
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .header("User-Agent", "AI-Workbench");
                if (token != null && !token.isBlank()) request.header("Authorization", "Bearer " + token.trim());
                if (etag != null && previous != null) request.header("If-None-Match", etag);
                var response = request.retrieve().toEntity(JsonNode.class);
                int status = response.getStatusCode().value();
                if (status == 304 && previous != null) {
                    return remember(address, new Snapshot(previous, etag, timestamp()));
                }
                if (status == 301 || status == 302 || status == 307 || status == 308) {
                    URI location = response.getHeaders().getLocation();
                    if (location == null) throw unavailable("GitHub 返回了无效的项目地址。");
                    uri = uri.resolve(location);
                    if (!safeApiUri(uri)) throw unavailable("GitHub 返回了不受支持的跳转地址。");
                    continue;
                }
                if (status != 200 || response.getBody() == null) throw unavailable("GitHub 未返回有效的项目概况。");
                JsonNode body = response.getBody();
                if (body.path("private").asBoolean()) throw unavailable("当前仅支持公开的 GitHub 仓库。");
                GithubAddress canonical = GithubAddress.parse(body.path("html_url").asText());
                long remoteId = body.path("id").asLong();
                if (remoteId <= 0) throw unavailable("GitHub 返回的项目标识无效。");
                List<String> topics = new ArrayList<>();
                if (body.path("topics").isArray()) body.path("topics").forEach(topic -> topics.add(topic.asText()));
                var metadata = new GithubModels.Metadata(
                        remoteId, body.path("full_name").asText(canonical.fullName()), canonical.url(),
                        nullable(body, "description"), nullable(body, "language"),
                        Math.max(0, body.path("stargazers_count").asLong()), Math.max(0, body.path("forks_count").asLong()),
                        List.copyOf(topics), body.path("archived").asBoolean(),
                        body.path("pushed_at").isTextual() ? OffsetDateTime.parse(body.path("pushed_at").asText()) : null);
                var snapshot = new Snapshot(metadata, response.getHeaders().getETag(), timestamp());
                remember(canonical, snapshot);
                return remember(address, snapshot);
            }
            throw unavailable("GitHub 仓库跳转次数过多，请检查地址。");
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 429 || (status == 403 && isRateLimited(exception.getResponseHeaders()))) {
                blockedUntil = retryAt(exception.getResponseHeaders(), now);
                throw unavailable("GitHub 请求已限流，请稍后同步；可在服务端配置 GITHUB_TOKEN。");
            }
            if (status == 404) throw unavailable("仓库不存在或不是公开仓库，请检查地址。");
            if (status == 401 || status == 403) throw unavailable("GitHub 拒绝访问，请检查服务端 Token 或仓库权限。");
            throw unavailable("GitHub 暂时不可用，请稍后同步。");
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("获取 GitHub 概况失败，请检查网络后重试。");
        }
    }

    private Snapshot remember(GithubAddress address, Snapshot snapshot) {
        cache.put(address.fullName(), snapshot);
        while (cache.size() > 200) cache.remove(cache.keySet().iterator().next());
        return snapshot;
    }

    private OffsetDateTime timestamp() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static boolean safeApiUri(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && "api.github.com".equalsIgnoreCase(uri.getHost())
                && uri.getPort() == -1 && uri.getUserInfo() == null && uri.getRawQuery() == null
                && uri.getRawFragment() == null
                && (uri.getPath().startsWith("/repos/") || uri.getPath().matches("/repositories/[0-9]+"));
    }

    private static boolean isRateLimited(HttpHeaders headers) {
        return headers != null && ("0".equals(headers.getFirst("X-RateLimit-Remaining")) || headers.containsKey("Retry-After"));
    }

    private static Instant retryAt(HttpHeaders headers, Instant now) {
        Instant retry = now.plusSeconds(60);
        if (headers == null) return retry;
        for (String name : List.of("Retry-After", "X-RateLimit-Reset")) {
            try {
                String value = headers.getFirst(name);
                if (value != null) {
                    Instant candidate = name.equals("Retry-After") ? now.plusSeconds(Long.parseLong(value))
                            : Instant.ofEpochSecond(Long.parseLong(value));
                    if (candidate.isAfter(retry)) retry = candidate;
                }
            } catch (NumberFormatException ignored) {
                // 未知的限流头采用至少一分钟的退避。
            }
        }
        return retry;
    }

    private static String nullable(JsonNode node, String key) {
        return node.path(key).isTextual() ? node.path(key).asText() : null;
    }

    private static ApiException unavailable(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_UNAVAILABLE", message);
    }
}
