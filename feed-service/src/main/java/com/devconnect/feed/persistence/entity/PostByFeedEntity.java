package com.devconnect.feed.persistence.entity;

import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

@Table("posts_by_feed")
public class PostByFeedEntity {

    @PrimaryKey
    private PostByFeedKey key;

    @Column("author_id")
    private String authorId;

    @Column("content")
    private String content;

    public PostByFeedEntity() {
    }

    public PostByFeedEntity(PostByFeedKey key, String authorId, String content) {
        this.key = key;
        this.authorId = authorId;
        this.content = content;
    }

    public PostByFeedKey getKey() {
        return key;
    }

    public String getAuthorId() {
        return authorId;
    }

    public String getContent() {
        return content;
    }
}
