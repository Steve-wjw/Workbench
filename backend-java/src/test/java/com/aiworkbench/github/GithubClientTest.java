package com.aiworkbench.github;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import com.aiworkbench.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GithubClientTest {
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer upstream = MockRestServiceServer.bindTo(builder).build();
    private final MutableClock clock = new MutableClock();
    private final GithubClient client = new GithubClient(builder.build(), "", clock);
    private final GithubAddress address = GithubAddress.parse("https://github.com/owner/project");
    private static final String BODY = """
            {"id":9007199254740993,"full_name":"Owner/Project","html_url":"https://github.com/Owner/Project",
             "private":false,"description":"An example","language":"Java","stargazers_count":123,
             "forks_count":8,"topics":["agent","java"],"archived":true,"pushed_at":"2026-11-01T00:00:00Z"}
            """;

    @Test
    void readsOfficialMetadataWithoutAnonymousAuthorizationAndCachesPreviewThenSave() {
        upstream.expect(requestTo("https://api.github.com/repos/owner/project"))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON).header(HttpHeaders.ETAG, "\"first\""));
        var result = client.fetch(address, null, null, false);
        assertEquals(9_007_199_254_740_993L, result.metadata().githubRepoId());
        assertEquals("https://github.com/owner/project", result.metadata().repositoryUrl());
        assertEquals(123, result.metadata().stars());
        assertEquals(2, result.metadata().topics().size());
        assertTrue(result.metadata().archived());
        assertSame(result, client.fetch(address, null, null, false));
        assertSame(result, client.fetch(address, null, null, true));
        upstream.verify();
    }

    @Test
    void conditionalRefreshPreservesMetadataAndAdvancesSuccessfulSyncTime() {
        upstream.expect(anything()).andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON).header(HttpHeaders.ETAG, "\"first\""));
        upstream.expect(header(HttpHeaders.IF_NONE_MATCH, "\"first\"")).andRespond(withStatus(HttpStatus.NOT_MODIFIED));
        var first = client.fetch(address, null, null, false);
        clock.advance(Duration.ofMinutes(6));
        var refreshed = client.fetch(address, first.etag(), first.metadata(), true);
        assertEquals(first.metadata(), refreshed.metadata());
        assertTrue(refreshed.fetchedAt().isAfter(first.fetchedAt()));
        upstream.verify();
    }

    @Test
    void optionalTokenIsOnlySentToOfficialApi() {
        upstream.expect(requestTo("https://api.github.com/repos/owner/project"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));
        new GithubClient(builder.build(), "test-token", clock).fetch(address, null, null, false);
        upstream.verify();
    }

    @Test
    void honorsRetryAfterAndDoesNotKeepRequestingDuringRateLimit() {
        upstream.expect(anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "120"));
        upstream.expect(anything()).andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));
        assertThrows(ApiException.class, () -> client.fetch(address, null, null, false));
        clock.advance(Duration.ofSeconds(70));
        assertThrows(ApiException.class, () -> client.fetch(address, null, null, true));
        clock.advance(Duration.ofSeconds(60));
        assertNotNull(client.fetch(address, null, null, false).metadata());
        upstream.verify();
    }

    @Test
    void honorsRateLimitResetOnForbiddenResponses() {
        upstream.expect(anything()).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .header("X-RateLimit-Remaining", "0").header("X-RateLimit-Reset", Long.toString(clock.instant().plusSeconds(600).getEpochSecond())));
        assertThrows(ApiException.class, () -> client.fetch(address, null, null, false));
        clock.advance(Duration.ofSeconds(120));
        assertThrows(ApiException.class, () -> client.fetch(address, null, null, false));
        upstream.verify();
    }

    @Test
    void followsOnlyOfficialRepositoryRedirects() {
        upstream.expect(anything()).andRespond(withStatus(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, "https://api.github.com/repositories/123"));
        upstream.expect(requestTo("https://api.github.com/repositories/123")).andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));
        assertEquals("Owner/Project", client.fetch(address, null, null, false).metadata().fullName());
        upstream.verify();
    }

    @Test
    void rejectsRedirectToAnotherHostBeforeSendingToken() {
        upstream.expect(anything()).andRespond(withStatus(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, "https://example.com/token-collector"));
        var tokenClient = new GithubClient(builder.build(), "test-token", clock);
        assertThrows(ApiException.class, () -> tokenClient.fetch(address, null, null, false));
        upstream.verify();
    }

    @Test
    void missingRepositoryAndInvalidResponsesAreNotSuccessfulEmptyMetadata() {
        upstream.expect(anything()).andRespond(withResourceNotFound());
        upstream.expect(anything()).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        upstream.expect(anything()).andRespond(withSuccess(BODY.replace("\"private\":false", "\"private\":true"), MediaType.APPLICATION_JSON));
        for (int i = 0; i < 3; i++) assertThrows(ApiException.class, () -> client.fetch(address, null, null, false));
        upstream.verify();
    }

    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-08T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
