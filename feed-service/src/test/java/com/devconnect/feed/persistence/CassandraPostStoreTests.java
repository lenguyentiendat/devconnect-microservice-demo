package com.devconnect.feed.persistence;

import com.devconnect.feed.dto.PostResponse;
import com.devconnect.feed.persistence.entity.PostByFeedEntity;
import com.devconnect.feed.persistence.entity.PostByFeedKey;
import com.devconnect.feed.persistence.entity.PostByIdEntity;
import com.devconnect.feed.persistence.repository.PostByFeedRepository;
import com.devconnect.feed.persistence.repository.PostByIdRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.cassandra.core.CassandraBatchOperations;
import org.springframework.data.cassandra.core.CassandraOperations;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CassandraPostStoreTests {

    @Mock
    private CassandraOperations cassandraOperations;

    @Mock
    private CassandraBatchOperations batchOperations;

    @Mock
    private PostByFeedRepository postByFeedRepository;

    @Mock
    private PostByIdRepository postByIdRepository;

    private CassandraPostStore postStore;

    @BeforeEach
    void setUp() {
        postStore = new CassandraPostStore(
                cassandraOperations,
                postByFeedRepository,
                postByIdRepository
        );
    }

    @Test
    void saveWritesBothQueryModelsInOneLoggedBatch() {
        UUID postId = UUID.randomUUID();
        PostResponse post = new PostResponse(
                postId.toString(),
                "u001",
                "Cassandra",
                LocalDateTime.of(2026, 7, 14, 8, 30)
        );
        when(cassandraOperations.batchOps()).thenReturn(batchOperations);

        postStore.save(post);

        verify(batchOperations).insert(
                isA(PostByFeedEntity.class),
                isA(PostByIdEntity.class)
        );
        verify(batchOperations).execute();
    }

    @Test
    void findByIdMapsCassandraTimestampBackToApiModel() {
        UUID postId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-14T08:30:00Z");
        when(postByIdRepository.findById(postId)).thenReturn(Optional.of(
                new PostByIdEntity(postId, "u001", "Cassandra", createdAt)
        ));

        PostResponse result = postStore.findById(postId.toString()).orElseThrow();

        assertEquals(postId.toString(), result.postId());
        assertEquals("u001", result.authorId());
        assertEquals("Cassandra", result.content());
        assertEquals(LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC), result.createdAt());
    }

    @Test
    void invalidPostIdReturnsEmptyWithoutQueryingCassandra() {
        assertTrue(postStore.findById("not-a-uuid").isEmpty());
        verifyNoInteractions(postByIdRepository);
    }

    @Test
    void findAllPreservesCassandraClusteringOrder() {
        Instant newest = Instant.parse("2026-07-14T09:00:00Z");
        Instant older = Instant.parse("2026-07-14T08:00:00Z");
        PostByFeedEntity first = new PostByFeedEntity(
                new PostByFeedKey(CassandraPostStore.GLOBAL_FEED, newest, UUID.randomUUID()),
                "u001",
                "Newest"
        );
        PostByFeedEntity second = new PostByFeedEntity(
                new PostByFeedKey(CassandraPostStore.GLOBAL_FEED, older, UUID.randomUUID()),
                "u002",
                "Older"
        );
        when(postByFeedRepository.findByFeedId(CassandraPostStore.GLOBAL_FEED))
                .thenReturn(List.of(first, second));

        List<PostResponse> result = postStore.findAll();

        assertEquals(List.of("Newest", "Older"), result.stream().map(PostResponse::content).toList());
    }
}
