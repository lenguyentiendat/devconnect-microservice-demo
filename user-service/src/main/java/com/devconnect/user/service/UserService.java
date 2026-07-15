package com.devconnect.user.service;

import com.devconnect.user.dto.UserResponse;
import com.devconnect.user.dto.UserStatusResponse;
import com.devconnect.user.exception.UserAlreadyExistsException;
import com.devconnect.user.exception.UserNotFoundException;
import com.devconnect.user.persistence.UserEntity;
import com.devconnect.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

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

    public UserResponse createUser(String userId, String status) {
        if (userRepository.existsById(userId)) {
            throw new UserAlreadyExistsException("User already exists");
        }

        try {
            UserEntity saved = userRepository.saveAndFlush(new UserEntity(userId, status));
            return toResponse(saved);
        } catch (DataIntegrityViolationException exception) {
            throw new UserAlreadyExistsException("User already exists");
        }
    }

    public UserResponse updateUser(String userId, String status) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found"));
        user.updateStatus(status);
        return toResponse(userRepository.saveAndFlush(user));
    }

    private UserResponse toResponse(UserEntity user) {
        return new UserResponse(user.getUserId(), user.getStatus());
    }
}
