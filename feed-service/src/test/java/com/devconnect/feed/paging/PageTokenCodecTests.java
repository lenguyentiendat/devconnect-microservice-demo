package com.devconnect.feed.paging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PageTokenCodecTests {

    private PageTokenCodec codec;

    @BeforeEach
    void setUp() {
        codec = new PageTokenCodec("test-page-token-secret");
    }

    @Test
    void tokenRoundTripReturnsOriginalPagingState() {
        ByteBuffer state = ByteBuffer.wrap(new byte[] {1, 2, 3});

        assertThat(codec.decode(codec.encode(state))).isEqualTo(state);
    }

    @Test
    void tamperedTokenIsRejected() {
        String validToken = codec.encode(ByteBuffer.wrap(new byte[] {1, 2, 3}));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.decode(validToken + "x"))
                .withMessage("Invalid page token");
    }

    @Test
    void malformedTokenIsRejectedWithoutExposingItsValue() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.decode("not-a-valid-token"))
                .withMessage("Invalid page token");
    }

    @Test
    void nullTokenIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.decode(null))
                .withMessage("Invalid page token");
    }
}
