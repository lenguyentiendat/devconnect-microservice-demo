package com.devconnect.feed;

import com.devconnect.feed.client.UserServiceClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;

@EnableFeignClients(clients = UserServiceClient.class)
@SpringBootApplication
@OpenAPIDefinition(
		info = @Info(
				title = "DevConnect Feed Service API",
				version = "1.0.0",
				description = "Create and read feed posts with author validation."
		),
		servers = @Server(url = "http://localhost:8082")
)
public class FeedServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(FeedServiceApplication.class, args);
	}

}
