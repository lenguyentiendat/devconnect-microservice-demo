package com.devconnect.feed.client;

import com.devconnect.feed.dto.ApiResponse;
import com.devconnect.feed.dto.UserStatusResponse;
import com.devconnect.feed.exception.BusinessException;
import com.devconnect.feed.exception.DownstreamServiceException;
import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserServiceAdapterTests {

    private UserServiceClient client;
    private UserServiceAdapter adapter;

    @BeforeEach
    void setUp() {
        client = mock(UserServiceClient.class);
        adapter = new UserServiceAdapter(client);
    }

    @Test
    void returnsUserStatusFromSuccessfulEnvelope() {
        UserStatusResponse expected = new UserStatusResponse("u001", "ACTIVE");
        when(client.getUserStatus("u001"))
                .thenReturn(ApiResponse.success("User status found", expected));

        assertEquals(expected, adapter.getUserStatus("u001"));
    }

    @Test
    void rejectsInvalidResponseEnvelopeAsMissingAuthor() {
        when(client.getUserStatus("u404"))
                .thenReturn(ApiResponse.error("User not found"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> adapter.getUserStatus("u404")
        );

        assertEquals("Author not found", exception.getMessage());
    }

    @Test
    void mapsFeignNotFoundToMissingAuthor() {
        when(client.getUserStatus("u404")).thenThrow(feignException(404));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> adapter.getUserStatus("u404")
        );

        assertEquals("Author not found", exception.getMessage());
    }

    @Test
    void mapsOtherFeignFailuresToDownstreamFailure() {
        when(client.getUserStatus("u001")).thenThrow(feignException(503));

        DownstreamServiceException exception = assertThrows(
                DownstreamServiceException.class,
                () -> adapter.getUserStatus("u001")
        );

        assertEquals("Failed to call User Service", exception.getMessage());
    }

    private FeignException feignException(int status) {
        Request request = Request.create(
                Request.HttpMethod.GET,
                "/internal/users/u001/status",
                Map.of(),
                null,
                StandardCharsets.UTF_8,
                null
        );
        Response response = Response.builder()
                .status(status)
                .reason("test")
                .request(request)
                .headers(Map.of())
                .build();
        return FeignException.errorStatus("getUserStatus", response);
    }
}
