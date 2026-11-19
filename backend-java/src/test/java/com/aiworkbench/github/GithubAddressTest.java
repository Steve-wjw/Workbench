package com.aiworkbench.github;

import com.aiworkbench.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GithubAddressTest {
    @Test
    void normalizesCaseTrailingSlashAndGitSuffix() {
        for (String url : new String[]{
                "https://github.com/OpenAI/Agents", " HTTPS://GITHUB.COM/OpenAI/Agents.git/ ",
                "https://github.com/openai/agents/"}) {
            var address = GithubAddress.parse(url);
            assertEquals("openai/agents", address.fullName());
            assertEquals("https://github.com/openai/agents", address.url());
        }
    }

    @Test
    void rejectsArbitraryHostsCredentialsNestedPathsAndEncodedPaths() {
        for (String url : new String[]{
                "", "openai/agents", "http://github.com/openai/agents", "https://api.github.com/repos/openai/agents",
                "https://github.com.evil.test/a/b", "https://github.com@localhost/a/b",
                "https://github.com:443/a/b", "https://github.com/a/b/tree/main", "https://github.com/a/b?token=secret",
                "https://github.com/a/b#readme", "https://github.com/a/%2e%2e", "https://github.com/a/..",
                "https://github.com/a/b%2fc", "https://github.com/a/b\nHeader: value"}) {
            assertThrows(ApiException.class, () -> GithubAddress.parse(url), url);
        }
        assertThrows(ApiException.class, () -> GithubAddress.parse(null));
    }
}
