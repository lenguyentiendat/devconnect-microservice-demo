package com.devconnect.user.service;

import com.devconnect.user.dto.UserStatusResponse;
import com.devconnect.user.repository.UserRepository;
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
}
