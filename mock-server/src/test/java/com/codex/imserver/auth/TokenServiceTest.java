package com.codex.imserver.auth;

import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TokenServiceTest {
    @Test
    public void issuedTokenVerifiesUntilExpiry() {
        TokenService service = new TokenService("test-secret", () -> 1_000L, 60_000L, 120_000L, () -> "refresh-token-a");

        TokenService.IssuedToken issued = service.issue("13800138000");

        assertTrue(issued.token().startsWith("mock-jwt."));
        assertEquals(61_000L, issued.expiresAtMillis());
        assertEquals(Optional.of("13800138000"), service.verify(issued.token()));
    }

    @Test
    public void verifyRejectsLegacyOrExpiredTokens() {
        MutableClock clock = new MutableClock(1_000L);
        TokenService service = new TokenService("test-secret", clock::now, 60_000L, 120_000L, () -> "refresh-token-a");
        TokenService.IssuedToken issued = service.issue("13800138000");

        clock.now = 61_001L;

        assertTrue(service.verify("mock-token-13800138000").isEmpty());
        assertTrue(service.verify(issued.token()).isEmpty());
    }

    @Test
    public void refreshTokenHashDoesNotExposePlaintextToken() {
        TokenService service = new TokenService("test-secret", () -> 1_000L, 60_000L, 120_000L, () -> "refresh-token-a");

        TokenService.IssuedRefreshToken refreshToken = service.issueRefreshToken();

        assertEquals("refresh-token-a", refreshToken.token());
        assertEquals(121_000L, refreshToken.expiresAtMillis());
        assertTrue(refreshToken.hash().length() > 40);
        assertTrue(!refreshToken.hash().contains(refreshToken.token()));
    }

    private static final class MutableClock {
        private long now;

        private MutableClock(long now) {
            this.now = now;
        }

        private long now() {
            return now;
        }
    }
}
