package com.aiworkbench.github;

import java.net.URI;
import java.util.Locale;

import com.aiworkbench.common.ApiException;
import org.springframework.http.HttpStatus;

/** 仅接受仓库根地址；用户输入永远不会直接用作后端请求地址。 */
record GithubAddress(String fullName) {
    static GithubAddress parse(String input) {
        try {
            URI uri = URI.create(input == null ? "" : input.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"github.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || uri.getPort() != -1
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
            String path = uri.getRawPath().replaceAll("/+$", "");
            if (path.endsWith(".git")) path = path.substring(0, path.length() - 4);
            if (!path.matches("/[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9_.-]{1,100}")) {
                throw new IllegalArgumentException();
            }
            String name = path.substring(1);
            String repository = name.substring(name.indexOf('/') + 1);
            if (repository.equals(".") || repository.equals("..")) throw new IllegalArgumentException();
            return new GithubAddress(name.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GITHUB_URL",
                    "请输入完整的 HTTPS GitHub 仓库地址，例如 https://github.com/owner/repository");
        }
    }

    String url() {
        return "https://github.com/" + fullName;
    }
}
