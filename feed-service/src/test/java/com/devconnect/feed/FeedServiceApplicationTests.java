package com.devconnect.feed;

import com.datastax.oss.driver.api.core.CqlSession;
import com.devconnect.feed.client.UserServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(properties = "spring.cassandra.schema-action=none")
class FeedServiceApplicationTests {

	@MockitoBean
	private CqlSession cqlSession;

	@Autowired
	private UserServiceClient userServiceClient;

	@Test
	void contextLoads() {
		assertNotNull(userServiceClient);
	}

}
