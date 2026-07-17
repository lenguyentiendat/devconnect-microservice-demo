package com.devconnect.user;

import com.devconnect.user.service.UserService;
import com.devconnect.user.persistence.UserEntity;
import com.devconnect.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class UserServiceApplicationTests {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Test
    void contextLoads() {
    }

    @Test
    void flywaySeedsDemoUsers() {
        var activeUser = userService.getUserStatus("u001");
        var inactiveUser = userService.getUserStatus("u003");

        assertTrue(activeUser.isPresent());
        assertEquals("ACTIVE", activeUser.orElseThrow().status());
        assertTrue(inactiveUser.isPresent());
        assertEquals("INACTIVE", inactiveUser.orElseThrow().status());
        assertTrue(userService.getUserStatus("unknown").isEmpty());
    }

    @Test
    void createsAndUpdatesUserAgainstFlywaySchema() {
        String userId = "integration-user";

        var created = userService.createUser(
                userId, "ACTIVE", " Integration@Example.COM "
        );
        var updated = userService.updateUser(
                userId, "INACTIVE", "updated@example.com"
        );
        var persisted = userService.getUserStatus(userId).orElseThrow();

        assertEquals("ACTIVE", created.status());
        assertEquals("integration@example.com", created.email());
        assertEquals("INACTIVE", updated.status());
        assertEquals("updated@example.com", updated.email());
        assertEquals("INACTIVE", persisted.status());
    }

    @Test
    void persistsNormalizedEmailAndKeepsLegacySeedNullable() {
        var saved = userRepository.saveAndFlush(
                new UserEntity("email-user", "ACTIVE", "  User@Example.COM  ")
        );

        assertEquals("user@example.com", saved.getEmail());
        assertNull(userRepository.findById("u001").orElseThrow().getEmail());
    }

    @Test
    void databaseRejectsEmailThatDiffersOnlyByCase() {
        userRepository.saveAndFlush(new UserEntity("email-a", "ACTIVE", "user@example.com"));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> userRepository.saveAndFlush(
                        new UserEntity("email-b", "ACTIVE", "USER@example.com")
                )
        );
    }

}
