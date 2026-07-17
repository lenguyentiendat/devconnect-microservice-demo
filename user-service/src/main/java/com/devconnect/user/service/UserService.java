package com.devconnect.user.service;

import com.devconnect.user.dto.UserResponse;
import com.devconnect.user.dto.UserStatusResponse;
import com.devconnect.user.exception.UserAlreadyExistsException;
import com.devconnect.user.exception.UserNotFoundException;
import com.devconnect.user.persistence.UserEntity;
import com.devconnect.user.repository.UserRepository;
import com.devconnect.user.support.EmailNormalizer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Optional<UserStatusResponse> getUserStatus(String userId) {
        return userRepository.findById(userId)
                .map(user -> new UserStatusResponse(user.getUserId(), user.getStatus()));
    }

    public UserResponse createUser(String userId, String status, String email) {
        String normalizedEmail = EmailNormalizer.normalize(email);
        if (userRepository.existsById(userId)) {
            throw new UserAlreadyExistsException("User already exists");
        }
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new UserAlreadyExistsException("Email already exists");
        }

        try {
            UserEntity saved = userRepository.saveAndFlush(
                    new UserEntity(userId, status, normalizedEmail)
            );
            return toResponse(saved);
        } catch (DataIntegrityViolationException exception) {
            if (isEmailConstraintViolation(exception)) {
                throw new UserAlreadyExistsException("Email already exists");
            }
            throw new UserAlreadyExistsException("User already exists");
        }
    }

    public UserResponse updateUser(String userId, String status, String email) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found"));
        String normalizedEmail = EmailNormalizer.normalize(email);
        if (normalizedEmail != null && !normalizedEmail.equals(user.getEmail())) {
            if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
                throw new UserAlreadyExistsException("Email already exists");
            }
            user.updateEmail(normalizedEmail);
        }
        user.updateStatus(status);
        try {
            return toResponse(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException exception) {
            throw new UserAlreadyExistsException("Email already exists");
        }
    }

    private UserResponse toResponse(UserEntity user) {
        return new UserResponse(user.getUserId(), user.getStatus(), user.getEmail());
    }

    private boolean isEmailConstraintViolation(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT)
                    .contains("users_email_lower_unique")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
