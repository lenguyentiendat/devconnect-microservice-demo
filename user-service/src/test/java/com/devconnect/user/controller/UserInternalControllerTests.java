package com.devconnect.user.controller;

import com.devconnect.user.dto.UserResponse;
import com.devconnect.user.dto.UserStatusResponse;
import com.devconnect.user.exception.UserAlreadyExistsException;
import com.devconnect.user.exception.UserNotFoundException;
import com.devconnect.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserInternalController.class)
@TestPropertySource(properties = "logging.level.com.devconnect.user.exception.GlobalExceptionHandler=OFF")
class UserInternalControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @Test
    void createsUser() throws Exception {
        when(userService.createUser("u004", "ACTIVE", "user@example.com"))
                .thenReturn(new UserResponse("u004", "ACTIVE", "user@example.com"));

        mockMvc.perform(post("/api/users")
                        .contentType("application/json")
                        .content("""
                                {"userId":"u004","status":"ACTIVE","email":"  User@Example.COM  "}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("User created successfully"))
                .andExpect(jsonPath("$.data.userId").value("u004"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.email").value("user@example.com"));
    }

    @Test
    void rejectsInvalidCreateStatus() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType("application/json")
                        .content("""
                                {"userId":"u004","status":"SUSPENDED","email":"user@example.com"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("status must be ACTIVE or INACTIVE"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(userService);
    }

    @Test
    void rejectsMissingCreateUserId() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType("application/json")
                        .content("""
                                {"status":"ACTIVE","email":"user@example.com"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("userId is required"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(userService);
    }

    @Test
    void rejectsOversizedCreateUserId() throws Exception {
        String userId = "x".repeat(65);

        mockMvc.perform(post("/api/users")
                        .contentType("application/json")
                        .content("""
                                {"userId":"%s","status":"ACTIVE","email":"user@example.com"}
                                """.formatted(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("userId must not exceed 64 characters"));

        verifyNoInteractions(userService);
    }

    @Test
    void rejectsMalformedCreateBody() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType("application/json")
                        .content("{not-json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Malformed request body"));

        verifyNoInteractions(userService);
    }

    @Test
    void rejectsDuplicateCreate() throws Exception {
        when(userService.createUser("u001", "ACTIVE", "user@example.com"))
                .thenThrow(new UserAlreadyExistsException("User already exists"));

        mockMvc.perform(post("/api/users")
                        .contentType("application/json")
                        .content("""
                                {"userId":"u001","status":"ACTIVE","email":"user@example.com"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("User already exists"));
    }

    @Test
    void rejectsMissingCreateEmail() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType("application/json")
                        .content("""
                                {"userId":"u004","status":"ACTIVE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("email is required"));

        verifyNoInteractions(userService);
    }

    @Test
    void rejectsInvalidCreateEmail() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType("application/json")
                        .content("""
                                {"userId":"u004","status":"ACTIVE","email":"not-an-email"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("email must be valid"));

        verifyNoInteractions(userService);
    }

    @Test
    void rejectsOversizedCreateEmail() throws Exception {
        String email = "a".repeat(243) + "@example.com";

        mockMvc.perform(post("/api/users")
                        .contentType("application/json")
                        .content("""
                                {"userId":"u004","status":"ACTIVE","email":"%s"}
                                """.formatted(email)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(userService);
    }

    @Test
    void rejectsDuplicateCreateEmail() throws Exception {
        when(userService.createUser("u004", "ACTIVE", "taken@example.com"))
                .thenThrow(new UserAlreadyExistsException("Email already exists"));

        mockMvc.perform(post("/api/users")
                        .contentType("application/json")
                        .content("""
                                {"userId":"u004","status":"ACTIVE","email":"TAKEN@EXAMPLE.COM"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Email already exists"));
    }

    @Test
    void updatesUser() throws Exception {
        when(userService.updateUser("u004", "INACTIVE", null))
                .thenReturn(new UserResponse("u004", "INACTIVE", "user@example.com"));

        mockMvc.perform(put("/api/users/u004")
                        .contentType("application/json")
                        .content("""
                                {"status":"INACTIVE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("User updated successfully"))
                .andExpect(jsonPath("$.data.userId").value("u004"))
                .andExpect(jsonPath("$.data.status").value("INACTIVE"))
                .andExpect(jsonPath("$.data.email").value("user@example.com"));
    }

    @Test
    void updatesAndNormalizesEmail() throws Exception {
        when(userService.updateUser("u004", "INACTIVE", "new@example.com"))
                .thenReturn(new UserResponse("u004", "INACTIVE", "new@example.com"));

        mockMvc.perform(put("/api/users/u004")
                        .contentType("application/json")
                        .content("""
                                {"status":"INACTIVE","email":"  New@Example.COM  "}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("new@example.com"));
    }

    @Test
    void rejectsBlankUpdateEmail() throws Exception {
        mockMvc.perform(put("/api/users/u004")
                        .contentType("application/json")
                        .content("""
                                {"status":"INACTIVE","email":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("email must not be blank"));

        verifyNoInteractions(userService);
    }

    @Test
    void rejectsInvalidUpdateEmail() throws Exception {
        mockMvc.perform(put("/api/users/u004")
                        .contentType("application/json")
                        .content("""
                                {"status":"INACTIVE","email":"not-an-email"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("email must be valid"));

        verifyNoInteractions(userService);
    }

    @Test
    void rejectsDuplicateUpdateEmail() throws Exception {
        when(userService.updateUser("u004", "INACTIVE", "taken@example.com"))
                .thenThrow(new UserAlreadyExistsException("Email already exists"));

        mockMvc.perform(put("/api/users/u004")
                        .contentType("application/json")
                        .content("""
                                {"status":"INACTIVE","email":"TAKEN@EXAMPLE.COM"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Email already exists"));
    }

    @Test
    void rejectsUpdateForUnknownUser() throws Exception {
        when(userService.updateUser("missing", "ACTIVE", null))
                .thenThrow(new UserNotFoundException("User not found"));

        mockMvc.perform(put("/api/users/missing")
                        .contentType("application/json")
                        .content("""
                                {"status":"ACTIVE"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("User not found"));
    }

    @Test
    void rejectsInvalidUpdateStatus() throws Exception {
        mockMvc.perform(put("/api/users/u004")
                        .contentType("application/json")
                        .content("""
                                {"status":"SUSPENDED"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("status must be ACTIVE or INACTIVE"));

        verifyNoInteractions(userService);
    }

    @Test
    void mapsUnexpectedServiceFailureToStandardError() throws Exception {
        when(userService.updateUser("u004", "ACTIVE", null))
                .thenThrow(new IllegalStateException("database unavailable"));

        mockMvc.perform(put("/api/users/u004")
                        .contentType("application/json")
                        .content("""
                                {"status":"ACTIVE"}
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Internal server error"));
    }

    @Test
    void keepsUnknownRouteAsNotFound() throws Exception {
        mockMvc.perform(get("/api/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void keepsInternalStatusEndpointCompatible() throws Exception {
        when(userService.getUserStatus("u001"))
                .thenReturn(Optional.of(new UserStatusResponse("u001", "ACTIVE")));

        mockMvc.perform(get("/internal/users/u001/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("User status found"))
                .andExpect(jsonPath("$.data.userId").value("u001"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.email").doesNotExist());
    }

    @Test
    void keepsInternalMissingUserResponseCompatible() throws Exception {
        when(userService.getUserStatus(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/internal/users/missing/status"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("User not found"));
    }
}
