package com.devconnect.feed.client;

import com.devconnect.feed.dto.ApiResponse;
import com.devconnect.feed.dto.UserStatusResponse;
import com.devconnect.feed.exception.BusinessException;
import com.devconnect.feed.exception.DownstreamServiceException;
import feign.FeignException;
import org.springframework.stereotype.Component;

@Component
public class UserServiceAdapter {

    private final UserServiceClient userServiceClient;

    public UserServiceAdapter(UserServiceClient userServiceClient) {
        this.userServiceClient = userServiceClient;
    }

    public UserStatusResponse getUserStatus(String userId) {
        try {
            ApiResponse<UserStatusResponse> response =
                    userServiceClient.getUserStatus(userId);

            if (response == null || !response.isSuccess() || response.getData() == null) {
                throw new BusinessException("Author not found");
            }

            return response.getData();
        } catch (FeignException.NotFound exception) {
            throw new BusinessException("Author not found");
        } catch (FeignException exception) {
            throw new DownstreamServiceException(
                    "Failed to call User Service",
                    exception
            );
        }
    }
}
