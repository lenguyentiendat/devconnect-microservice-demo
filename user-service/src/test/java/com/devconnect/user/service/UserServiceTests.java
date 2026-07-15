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
        when(userRepository.saveAndFlush(any(UserEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = userService.createUser("u004", "ACTIVE");

        assertEquals("u004", response.userId());
        assertEquals("ACTIVE", response.status());
        verify(userRepository).saveAndFlush(any(UserEntity.class));
    }

    @Test
    void rejectsExistingUserBeforeSave() {
        when(userRepository.existsById("u001")).thenReturn(true);

        var exception = assertThrows(
                UserAlreadyExistsException.class,
                () -> userService.createUser("u001", "ACTIVE")
        );

        assertEquals("User already exists", exception.getMessage());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void translatesConcurrentDuplicateAtDatabaseBoundary() {
        when(userRepository.existsById("u004")).thenReturn(false);
        when(userRepository.saveAndFlush(any(UserEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        var exception = assertThrows(
                UserAlreadyExistsException.class,
                () -> userService.createUser("u004", "ACTIVE")
        );

        assertEquals("User already exists", exception.getMessage());
    }

    @Test
    void updatesExistingUserStatus() {
        var entity = new UserEntity("u004", "ACTIVE");
        when(userRepository.findById("u004")).thenReturn(Optional.of(entity));
        when(userRepository.saveAndFlush(entity)).thenReturn(entity);

        var response = userService.updateUser("u004", "INACTIVE");

        assertEquals("u004", response.userId());
        assertEquals("INACTIVE", response.status());
        verify(userRepository).saveAndFlush(entity);
    }

    @Test
    void rejectsUpdateForUnknownUser() {
        when(userRepository.findById("missing")).thenReturn(Optional.empty());

        var exception = assertThrows(
                UserNotFoundException.class,
                () -> userService.updateUser("missing", "ACTIVE")
        );

        assertEquals("User not found", exception.getMessage());
        verify(userRepository, never()).saveAndFlush(any());
    }
}
