package com.devconnect.feed.paging;

import com.devconnect.feed.config.CacheProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;

@Component
public class PageTokenCodec {

    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
    private static final String HMAC_SHA_256 = "HmacSHA256";

    private final byte[] secret;

    @Autowired
    public PageTokenCodec(CacheProperties properties) {
        this(properties.pageTokenSecret());
    }

    public PageTokenCodec(String secret) {
        this.secret = Objects.requireNonNull(secret, "secret must not be null")
                .getBytes(StandardCharsets.UTF_8);
    }

    public String encode(ByteBuffer pagingState) {
        Objects.requireNonNull(pagingState, "pagingState must not be null");
        String payload = BASE64_URL_ENCODER.encodeToString(copyBytes(pagingState));
        String signature = BASE64_URL_ENCODER.encodeToString(sign(payload));
        return payload + "." + signature;
    }

    public ByteBuffer decode(String token) {
        try {
            if (token == null) {
                throw invalidToken();
            }
            String[] segments = token.split("\\.", -1);
            if (segments.length != 2 || segments[0].isEmpty() || segments[1].isEmpty()) {
                throw invalidToken();
            }

            byte[] payload = BASE64_URL_DECODER.decode(segments[0]);
            byte[] providedSignature = BASE64_URL_DECODER.decode(segments[1]);
            if (!MessageDigest.isEqual(sign(segments[0]), providedSignature)) {
                throw invalidToken();
            }
            return ByteBuffer.wrap(payload);
        } catch (IllegalArgumentException exception) {
            throw invalidToken();
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(secret, HMAC_SHA_256));
            return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to sign page token", exception);
        }
    }

    private static byte[] copyBytes(ByteBuffer source) {
        ByteBuffer copy = source.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }

    private static IllegalArgumentException invalidToken() {
        return new IllegalArgumentException("Invalid page token");
    }
}
