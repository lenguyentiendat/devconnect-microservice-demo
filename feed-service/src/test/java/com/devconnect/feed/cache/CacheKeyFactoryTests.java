package com.devconnect.feed.cache;

import com.devconnect.feed.config.CacheProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CacheKeyFactoryTests {

    private final CacheKeyFactory keys = new CacheKeyFactory(new CacheProperties(
            true,
            "local",
            100,
            Duration.ofSeconds(45),
            Duration.ofMinutes(5),
            Duration.ofSeconds(10),
            Duration.ofMinutes(1),
            20,
            100,
            10,
            100,
            "devconnect:cache:invalidation",
            "page-token-secret"
    ));

    @Test
    void postAndRevisionKeysUseTheFeedNamespace() {
        assertThat(keys.post("post-123")).isEqualTo("devconnect:local:feed:v1:post:post-123");
        assertThat(keys.feedRevision("global")).isEqualTo("devconnect:local:feed:v1:revision:global");
    }

    @Test
    void pageKeyIncludesEnvironmentVersionRevisionSizeAndCursorHash() {
        assertThat(keys.feedPage("global", 7, 20, "opaque-token"))
                .startsWith("devconnect:local:feed:v1:page:global:rev:7:size:20:cursor:");
    }

    @Test
    void firstPageAndPrefixHaveStableLayouts() {
        assertThat(keys.feedPage("global", 1, 20, null))
                .isEqualTo("devconnect:local:feed:v1:page:global:rev:1:size:20:cursor:first");
        assertThat(keys.feedPagePrefix("global")).isEqualTo("devconnect:local:feed:v1:page:global:");
    }
}
