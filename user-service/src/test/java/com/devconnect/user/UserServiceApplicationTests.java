package com.devconnect.user;

import com.devconnect.user.service.UserService;
import com.devconnect.user.exception.UserAlreadyExistsException;
import com.devconnect.user.persistence.UserEntity;
import com.devconnect.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void hasNoAutomaticDemoUsers() {
        assertTrue(userService.getUserStatus("u001").isEmpty());
    }

    @Test
    void createsAndUpdatesUserAgainstHibernateManagedSchema() {
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
    void persistsNormalizedEmailAgainstHibernateManagedSchema() {
        var saved = userRepository.saveAndFlush(
                new UserEntity("email-user", "ACTIVE", "  User@Example.COM  ")
        );

        assertEquals("user@example.com", saved.getEmail());
        assertEquals("user@example.com", userRepository.findById("email-user").orElseThrow().getEmail());
    }

    @Test
    void rejectsEmailThatDiffersOnlyByCase() {
        userService.createUser("email-a", "ACTIVE", "user@example.com");

        assertThrows(
                UserAlreadyExistsException.class,
                () -> userService.createUser("email-b", "ACTIVE", "USER@example.com")
        );
    }

}
