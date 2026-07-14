package com.devconnect.feed.persistence.entity;

import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyClass;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@PrimaryKeyClass
public class PostByFeedKey implements Serializable {

    @PrimaryKeyColumn(name = "feed_id", ordinal = 0, type = PrimaryKeyType.PARTITIONED)
    private String feedId;

    @PrimaryKeyColumn(
            name = "created_at",
            ordinal = 1,
            type = PrimaryKeyType.CLUSTERED,
            ordering = Ordering.DESCENDING
    )
    private Instant createdAt;

    @PrimaryKeyColumn(name = "post_id", ordinal = 2, type = PrimaryKeyType.CLUSTERED)
    private UUID postId;

    public PostByFeedKey() {
    }

    public PostByFeedKey(String feedId, Instant createdAt, UUID postId) {
        this.feedId = feedId;
        this.createdAt = createdAt;
        this.postId = postId;
    }

    public String getFeedId() {
        return feedId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getPostId() {
        return postId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PostByFeedKey that)) {
            return false;
        }
        return Objects.equals(feedId, that.feedId)
                && Objects.equals(createdAt, that.createdAt)
                && Objects.equals(postId, that.postId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(feedId, createdAt, postId);
    }
}
