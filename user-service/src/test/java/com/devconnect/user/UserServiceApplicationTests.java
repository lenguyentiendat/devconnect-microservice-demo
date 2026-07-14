package com.devconnect.user;

import com.devconnect.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class UserServiceApplicationTests {

    @Autowired
    private UserService userService;

    @Test
    void contextLoads() {
    }

    @Test
    void flywaySeedsDemoUsers() {
        var activeUser = userService.getUserStatus("u001");
        var inactiveUser = userService.getUserStatus("u003");

        assertTrue(activeUser.isPresent());
        assertEquals("ACTIVE", activeUser.orElseThrow().status());
        assertTrue(inactiveUser.isPresent());
        assertEquals("INACTIVE", inactiveUser.orElseThrow().status());
        assertTrue(userService.getUserStatus("unknown").isEmpty());
    }

}
