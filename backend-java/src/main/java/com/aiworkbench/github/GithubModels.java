package com.aiworkbench.github;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class GithubModels {
    private GithubModels() {
    }

    public enum Category {
        PERSONAL_DEMO, LEARNING
    }

    public enum SyncStatus {
        PENDING, SUCCESS, FAILED
    }

    public record Metadata(
            @JsonSerialize(using = ToStringSerializer.class) Long githubRepoId,
            String fullName,
            String repositoryUrl,
            String description,
            String language,
            long stars,
            long forks,
            List<String> topics,
            boolean archived,
            OffsetDateTime pushedAt) {
    }

    public record ProjectResponse(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            Category category,
            String fullName,
            String repositoryUrl,
            @JsonSerialize(using = ToStringSerializer.class) Long githubRepoId,
            String description,
            String language,
            long stars,
            long forks,
            List<String> topics,
            boolean archived,
            OffsetDateTime pushedAt,
            String notes,
            SyncStatus syncStatus,
            String syncError,
            OffsetDateTime lastSyncedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    public record PreviewRequest(@NotBlank @Size(max = 500) String repositoryUrl) {
    }

    public record PreviewResponse(String fullName, String repositoryUrl, Metadata metadata, String warning) {
    }

    public record CreateRequest(
            @NotNull Category category,
            @NotBlank @Size(max = 500) String repositoryUrl,
            @Size(max = 50000) String notes) {
    }

    public record UpdateRequest(Category category, @Size(max = 50000) String notes) {
    }

    public record Counts(long personalDemo, long learning) {
    }

    /** ETag 只用于服务端条件请求，不作为可编辑的项目数据。 */
    public record StoredProject(ProjectResponse project, String etag) {
    }
}
