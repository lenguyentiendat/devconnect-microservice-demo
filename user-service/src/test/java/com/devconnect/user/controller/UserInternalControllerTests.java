package com.devconnect.user.controller;

import com.devconnect.user.dto.UserResponse;
import com.devconnect.user.dto.UserStatusResponse;
import com.devconnect.user.exception.UserAlreadyExistsException;
import com.devconnect.user.exception.UserNotFoundException;
import com.devconnect.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
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
class UserInternalControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @Test
    void createsUser() throws Exception {
        when(userService.createUser("u004", "ACTIVE"))
                .thenReturn(new UserResponse("u004", "ACTIVE"));

        mockMvc.perform(post("/api/users")
                        .contentType("application/json")
                        .content("""
                                {"userId":"u004","status":"ACTIVE"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("User created successfully"))
                .andExpect(jsonPath("$.data.userId").value("u004"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void rejectsInvalidCreateStatus() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType("application/json")
                        .content("""
                                {"userId":"u004","status":"SUSPENDED"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("status must be ACTIVE or INACTIVE"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(userService);
    }

    @Test
    void rejectsDuplicateCreate() throws Exception {
        when(userService.createUser("u001", "ACTIVE"))
                .thenThrow(new UserAlreadyExistsException("User already exists"));

        mockMvc.perform(post("/api/users")
                        .contentType("application/json")
                        .content("""
                                {"userId":"u001","status":"ACTIVE"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("User already exists"));
    }

    @Test
    void updatesUser() throws Exception {
        when(userService.updateUser("u004", "INACTIVE"))
                .thenReturn(new UserResponse("u004", "INACTIVE"));

        mockMvc.perform(put("/api/users/u004")
                        .contentType("application/json")
                        .content("""
                                {"status":"INACTIVE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("User updated successfully"))
                .andExpect(jsonPath("$.data.userId").value("u004"))
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));
    }

    @Test
    void rejectsUpdateForUnknownUser() throws Exception {
        when(userService.updateUser("missing", "ACTIVE"))
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
    void keepsInternalStatusEndpointCompatible() throws Exception {
        when(userService.getUserStatus("u001"))
                .thenReturn(Optional.of(new UserStatusResponse("u001", "ACTIVE")));

        mockMvc.perform(get("/internal/users/u001/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("User status found"))
                .andExpect(jsonPath("$.data.userId").value("u001"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
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
