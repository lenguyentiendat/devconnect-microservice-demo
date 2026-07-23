package com.devconnect.feed.persistence;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.devconnect.feed.dto.FeedPage;
import com.devconnect.feed.dto.PostResponse;
import com.devconnect.feed.persistence.entity.PostByFeedEntity;
import com.devconnect.feed.persistence.entity.PostByFeedKey;
import com.devconnect.feed.persistence.entity.PostByIdEntity;
import com.devconnect.feed.persistence.repository.PostByFeedRepository;
import com.devconnect.feed.persistence.repository.PostByIdRepository;
import org.springframework.data.cassandra.core.CassandraBatchOperations;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CassandraPostStore implements PostStore {

    static final String GLOBAL_FEED = "global";

    private final CassandraOperations cassandraOperations;
    private final PostByFeedRepository postByFeedRepository;
    private final PostByIdRepository postByIdRepository;
    private final CqlSession cqlSession;

    public CassandraPostStore(
            CassandraOperations cassandraOperations,
            PostByFeedRepository postByFeedRepository,
            PostByIdRepository postByIdRepository,
            CqlSession cqlSession
    ) {
        this.cassandraOperations = cassandraOperations;
        this.postByFeedRepository = postByFeedRepository;
        this.postByIdRepository = postByIdRepository;
        this.cqlSession = cqlSession;
    }

    @Override
    public void save(PostResponse post) {
        UUID postId = UUID.fromString(post.postId());
        Instant createdAt = post.createdAt().toInstant(ZoneOffset.UTC);
        PostByFeedEntity feedRow = new PostByFeedEntity(
                new PostByFeedKey(GLOBAL_FEED, createdAt, postId),
                post.authorId(),
                post.content()
        );
        PostByIdEntity idRow = new PostByIdEntity(
                postId,
                post.authorId(),
                post.content(),
                createdAt
        );

        CassandraBatchOperations batch = cassandraOperations.batchOps();
        batch.insert(feedRow, idRow);
        batch.execute();
    }

    @Override
    public Optional<PostResponse> findById(String postId) {
        try {
            return postByIdRepository.findById(UUID.fromString(postId))
                    .map(this::toResponse);
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public FeedPage findPage(int pageSize, Instant lastCreatedAt, UUID lastPostId) {
        int requested = pageSize + 1;
        List<PostResponse> rows = new ArrayList<>();
        if (lastCreatedAt == null) {
            rows.addAll(query("SELECT post_id, author_id, content, created_at FROM posts_by_feed WHERE feed_id = ? LIMIT ?", GLOBAL_FEED, requested));
        } else {
            rows.addAll(query("SELECT post_id, author_id, content, created_at FROM posts_by_feed WHERE feed_id = ? AND created_at = ? AND post_id > ? LIMIT ?", GLOBAL_FEED, lastCreatedAt, lastPostId, requested));
            if (rows.size() < requested) rows.addAll(query("SELECT post_id, author_id, content, created_at FROM posts_by_feed WHERE feed_id = ? AND created_at < ? LIMIT ?", GLOBAL_FEED, lastCreatedAt, requested - rows.size()));
        }
        boolean hasNext = rows.size() > pageSize;
        if (hasNext) rows = rows.subList(0, pageSize);
        return new FeedPage(List.copyOf(rows), hasNext);
    }

    @Override
    public List<PostResponse> findAll() {
        return postByFeedRepository.findByFeedId(GLOBAL_FEED).stream()
                .map(this::toResponse)
                .toList();
    }

    private PostResponse toResponse(PostByFeedEntity entity) {
        return new PostResponse(
                entity.getKey().getPostId().toString(),
                entity.getAuthorId(),
                entity.getContent(),
                LocalDateTime.ofInstant(entity.getKey().getCreatedAt(), ZoneOffset.UTC)
        );
    }

    private PostResponse toResponse(PostByIdEntity entity) {
        return new PostResponse(
                entity.getPostId().toString(),
                entity.getAuthorId(),
                entity.getContent(),
                LocalDateTime.ofInstant(entity.getCreatedAt(), ZoneOffset.UTC)
        );
    }

    private List<PostResponse> query(String cql, Object... values) {
        var builder = SimpleStatement.builder(cql);
        for (Object value : values) builder.addPositionalValue(value);
        ResultSet result = cqlSession.execute(builder.build());
        List<PostResponse> items = new ArrayList<>();
        result.forEach(row -> items.add(toResponse(row)));
        return items;
    }

    private PostResponse toResponse(Row row) {
        return new PostResponse(
                row.getUuid("post_id").toString(),
                row.getString("author_id"),
                row.getString("content"),
                LocalDateTime.ofInstant(row.getInstant("created_at"), ZoneOffset.UTC)
        );
    }

}
