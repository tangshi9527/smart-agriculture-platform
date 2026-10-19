package com.smartagri.auth;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {

    private JwtTokenProvider newProvider(long expirationMs) {
        return new JwtTokenProvider(
                "test-secret-key-which-is-long-enough-32bytes-ok!!", expirationMs);
    }

    @Test
    void generateAndParse_roundTrip() {
        JwtTokenProvider p = newProvider(86_400_000L);
        String token = p.generateToken("alice", "ADMIN");
        assertTrue(p.validate(token));
        assertEquals("alice", p.getUsername(token));
        Claims claims = p.parse(token);
        assertEquals("ADMIN", claims.get("role"));
    }

    @Test
    void invalidToken_failsValidation() {
        JwtTokenProvider p = newProvider(86_400_000L);
        assertFalse(p.validate("not-a-real-token"));
        String token = p.generateToken("alice", "ADMIN");
        // 篡改签名段首字符（替换为另一个 base64url 合法字符）-> 签名校验必失败
        int lastDot = token.lastIndexOf('.');
        char orig = token.charAt(lastDot + 1);
        char replacement = (orig == 'A') ? 'B' : 'A';
        StringBuilder sb = new StringBuilder(token);
        sb.setCharAt(lastDot + 1, replacement);
        assertFalse(p.validate(sb.toString()));
    }

    @Test
    void expiredToken_failsValidation() {
        JwtTokenProvider p = newProvider(-1000L); // already expired
        String token = p.generateToken("alice", "USER");
        assertFalse(p.validate(token));
    }

    @Test
    void tokenSignedByAnotherKey_isRejected() {
        JwtTokenProvider p1 = newProvider(86_400_000L);
        JwtTokenProvider p2 = newProvider(86_400_000L);
        // 通过反射无法改 key，这里用不同密钥构造的 provider 校验对方令牌应失败
        JwtTokenProvider other = new JwtTokenProvider(
                "a-completely-different-secret-key-32bytes!!", 86_400_000L);
        String token = p1.generateToken("alice", "USER");
        assertFalse(other.validate(token));
    }
}
