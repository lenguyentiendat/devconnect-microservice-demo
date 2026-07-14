package com.devconnect.feed;

import com.datastax.oss.driver.api.core.CqlSession;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = "spring.cassandra.schema-action=none")
class FeedServiceApplicationTests {

	@MockitoBean
	private CqlSession cqlSession;

	@Test
	void contextLoads() {
	}

}
