package com.devconnect.feed.persistence.repository;

import com.devconnect.feed.persistence.entity.PostByFeedEntity;
import com.devconnect.feed.persistence.entity.PostByFeedKey;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;

import java.util.List;

public interface PostByFeedRepository extends CassandraRepository<PostByFeedEntity, PostByFeedKey> {

    @Query("SELECT * FROM posts_by_feed WHERE feed_id = ?0")
    List<PostByFeedEntity> findByFeedId(String feedId);
}
