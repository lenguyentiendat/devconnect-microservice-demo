package com.devconnect.feed.persistence.repository;

import com.devconnect.feed.persistence.entity.PostByIdEntity;
import org.springframework.data.cassandra.repository.CassandraRepository;

import java.util.UUID;

public interface PostByIdRepository extends CassandraRepository<PostByIdEntity, UUID> {
}
