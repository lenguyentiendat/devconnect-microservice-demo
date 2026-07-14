package com.devconnect.feed.persistence.entity;

import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("posts_by_id")
public class PostByIdEntity {

    @PrimaryKey("post_id")
    private UUID postId;

    @Column("author_id")
    private String authorId;

    @Column("content")
    private String content;

    @Column("created_at")
    private Instant createdAt;

    public PostByIdEntity() {
    }

    public PostByIdEntity(UUID postId, String authorId, String content, Instant createdAt) {
        this.postId = postId;
        this.authorId = authorId;
        this.content = content;
        this.createdAt = createdAt;
    }

    public UUID getPostId() {
        return postId;
    }

    public String getAuthorId() {
        return authorId;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
