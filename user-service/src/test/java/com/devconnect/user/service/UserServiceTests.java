package com.devconnect.user.service;

import com.devconnect.user.exception.UserAlreadyExistsException;
import com.devconnect.user.exception.UserNotFoundException;
import com.devconnect.user.persistence.UserEntity;
import com.devconnect.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTests {

    private UserRepository userRepository;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userService = new UserService(userRepository);
    }

    @Test
    void createsUser() {
        when(userRepository.existsById("u004")).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("user@example.com")).thenReturn(false);
        when(userRepository.saveAndFlush(any(UserEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = userService.createUser("u004", "ACTIVE", "  User@Example.COM  ");

        assertEquals("u004", response.userId());
        assertEquals("ACTIVE", response.status());
        assertEquals("user@example.com", response.email());
        verify(userRepository).existsByEmailIgnoreCase("user@example.com");
        verify(userRepository).saveAndFlush(any(UserEntity.class));
    }

    @Test
    void rejectsExistingUserBeforeSave() {
        when(userRepository.existsById("u001")).thenReturn(true);

        var exception = assertThrows(
                UserAlreadyExistsException.class,
                () -> userService.createUser("u001", "ACTIVE", "user@example.com")
        );

        assertEquals("User already exists", exception.getMessage());
        verify(userRepository, never()).existsByEmailIgnoreCase(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsEmailThatAlreadyExistsIgnoringCase() {
        when(userRepository.existsById("u004")).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("user@example.com")).thenReturn(true);

        var exception = assertThrows(
                UserAlreadyExistsException.class,
                () -> userService.createUser("u004", "ACTIVE", " USER@EXAMPLE.COM ")
        );

        assertEquals("Email already exists", exception.getMessage());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void translatesConcurrentDuplicateAtDatabaseBoundary() {
        when(userRepository.existsById("u004")).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("user@example.com")).thenReturn(false);
        when(userRepository.saveAndFlush(any(UserEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        var exception = assertThrows(
                UserAlreadyExistsException.class,
                () -> userService.createUser("u004", "ACTIVE", "user@example.com")
        );

        assertEquals("User already exists", exception.getMessage());
    }

    @Test
    void translatesConcurrentDuplicateEmailAtDatabaseBoundary() {
        when(userRepository.existsById("u004")).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("user@example.com")).thenReturn(false);
        when(userRepository.saveAndFlush(any(UserEntity.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate email",
                        new IllegalStateException("users_email_lower_unique")
                ));

        var exception = assertThrows(
                UserAlreadyExistsException.class,
                () -> userService.createUser("u004", "ACTIVE", "user@example.com")
        );

        assertEquals("Email already exists", exception.getMessage());
    }

    @Test
    void updatesExistingUserStatus() {
        var entity = new UserEntity("u004", "ACTIVE", "user@example.com");
        when(userRepository.findById("u004")).thenReturn(Optional.of(entity));
        when(userRepository.saveAndFlush(entity)).thenReturn(entity);

        var response = userService.updateUser("u004", "INACTIVE", null);

        assertEquals("u004", response.userId());
        assertEquals("INACTIVE", response.status());
        assertEquals("user@example.com", response.email());
        verify(userRepository, never()).existsByEmailIgnoreCase(any());
        verify(userRepository).saveAndFlush(entity);
    }

    @Test
    void updatesAndNormalizesEmail() {
        var entity = new UserEntity("u004", "ACTIVE", "old@example.com");
        when(userRepository.findById("u004")).thenReturn(Optional.of(entity));
        when(userRepository.existsByEmailIgnoreCase("new@example.com")).thenReturn(false);
        when(userRepository.saveAndFlush(entity)).thenReturn(entity);

        var response = userService.updateUser(
                "u004", "INACTIVE", "  New@Example.COM  "
        );

        assertEquals("INACTIVE", response.status());
        assertEquals("new@example.com", response.email());
        verify(userRepository).existsByEmailIgnoreCase("new@example.com");
        verify(userRepository).saveAndFlush(entity);
    }

    @Test
    void rejectsDuplicateEmailUpdateIgnoringCase() {
        var entity = new UserEntity("u004", "ACTIVE", "old@example.com");
        when(userRepository.findById("u004")).thenReturn(Optional.of(entity));
        when(userRepository.existsByEmailIgnoreCase("taken@example.com")).thenReturn(true);

        var exception = assertThrows(
                UserAlreadyExistsException.class,
                () -> userService.updateUser(
                        "u004", "INACTIVE", " TAKEN@EXAMPLE.COM "
                )
        );

        assertEquals("Email already exists", exception.getMessage());
        assertEquals("ACTIVE", entity.getStatus());
        assertEquals("old@example.com", entity.getEmail());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsUpdateForUnknownUser() {
        when(userRepository.findById("missing")).thenReturn(Optional.empty());

        var exception = assertThrows(
                UserNotFoundException.class,
                () -> userService.updateUser("missing", "ACTIVE", "user@example.com")
        );

        assertEquals("User not found", exception.getMessage());
        verify(userRepository, never()).saveAndFlush(any());
    }
}
