package com.devconnect.feed.persistence;

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
import java.util.Optional;
import java.util.UUID;

@Repository
public class CassandraPostStore implements PostStore {

    static final String GLOBAL_FEED = "global";

    private final CassandraOperations cassandraOperations;
    private final PostByFeedRepository postByFeedRepository;
    private final PostByIdRepository postByIdRepository;

    public CassandraPostStore(
            CassandraOperations cassandraOperations,
            PostByFeedRepository postByFeedRepository,
            PostByIdRepository postByIdRepository
    ) {
        this.cassandraOperations = cassandraOperations;
        this.postByFeedRepository = postByFeedRepository;
        this.postByIdRepository = postByIdRepository;
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
}
