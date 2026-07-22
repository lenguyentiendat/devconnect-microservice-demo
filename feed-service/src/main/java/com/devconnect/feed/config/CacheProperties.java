package com.devconnect.feed.config;

import com.devconnect.feed.cache.CacheTtls;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("app.cache")
public record CacheProperties(
        @DefaultValue("true") boolean enabled,
        @NotBlank @DefaultValue("local") String environment,
        @Positive @DefaultValue("10000") long l1MaximumSize,
        @DefaultValue("45s") Duration postL1Ttl,
        @DefaultValue("5m") Duration postL2Ttl,
        @DefaultValue("10s") Duration pageL1Ttl,
        @DefaultValue("60s") Duration pageL2Ttl,
        @Positive @DefaultValue("20") int feedDefaultPageSize,
        @Positive @DefaultValue("100") int feedMaximumPageSize,
        @Min(0) @Max(25) @DefaultValue("10") int ttlJitterPercent,
        @Positive @DefaultValue("500") int scanBatchSize,
        @NotBlank @DefaultValue("devconnect:cache:invalidation") String invalidationChannel,
        @NotBlank String pageTokenSecret
) {

    public CacheTtls postTtls() {
        return new CacheTtls(postL1Ttl, postL2Ttl);
    }

    public CacheTtls pageTtls() {
        return new CacheTtls(pageL1Ttl, pageL2Ttl);
    }

    @AssertTrue(message = "cache TTLs must be positive")
    public boolean hasPositiveTtls() {
        return isPositive(postL1Ttl) && isPositive(postL2Ttl)
                && isPositive(pageL1Ttl) && isPositive(pageL2Ttl);
    }

    private static boolean isPositive(Duration ttl) {
        return ttl != null && !ttl.isZero() && !ttl.isNegative();
    }
}
