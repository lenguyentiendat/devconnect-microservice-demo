package com.devconnect.feed.persistence;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PagingState;
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

import java.nio.ByteBuffer;
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
    public FeedPage findPage(int pageSize, ByteBuffer pagingState) {
        SimpleStatement statement = SimpleStatement.builder(
                        "SELECT post_id, author_id, content, created_at FROM posts_by_feed WHERE feed_id = ?")
                .addPositionalValue(GLOBAL_FEED)
                .setPageSize(pageSize)
                .setPagingState(pagingState)
                .build();
        ResultSet result = cqlSession.execute(statement);
        List<PostResponse> items = mapCurrentPage(result);
        PagingState nextPagingState = result.getExecutionInfo().getSafePagingState();

        return new FeedPage(items, copyPagingState(nextPagingState));
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

    private List<PostResponse> mapCurrentPage(ResultSet result) {
        int availableRows = result.getAvailableWithoutFetching();
        List<PostResponse> items = new ArrayList<>(availableRows);
        var rows = result.iterator();
        while (availableRows-- > 0 && rows.hasNext()) {
            items.add(toResponse(rows.next()));
        }
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

    private ByteBuffer copyPagingState(PagingState pagingState) {
        if (pagingState == null) {
            return null;
        }
        ByteBuffer source = pagingState.getRawPagingState().duplicate();
        ByteBuffer copy = ByteBuffer.allocate(source.remaining());
        copy.put(source);
        return copy.flip();
    }
}
