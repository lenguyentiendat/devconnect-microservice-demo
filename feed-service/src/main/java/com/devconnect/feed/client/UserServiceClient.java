package com.devconnect.feed.client;

import com.devconnect.feed.dto.ApiResponse;
import com.devconnect.feed.dto.UserStatusResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "user-service")
public interface UserServiceClient {

    @GetMapping("/internal/users/{userId}/status")
    ApiResponse<UserStatusResponse> getUserStatus(
            @PathVariable("userId") String userId
    );
}
