package com.yizhaoqi.smartpai.utils;

import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.service.TokenCacheService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtUtilsRefreshTest {
    private static final String SECRET = "dGVzdC1zZWNyZXQta2V5LWZvci1qd3QtdG9rZW4tZ2VuZXJhdGlvbi1hbmQtdmVyaWZpY2F0aW9u";
    private JwtUtils jwt;
    private TokenCacheService cache;
    private UserRepository users;
    @BeforeEach void setup() {
        jwt = new JwtUtils(); cache = mock(TokenCacheService.class); users = mock(UserRepository.class);
        ReflectionTestUtils.setField(jwt, "secretKeyBase64", SECRET);
        ReflectionTestUtils.setField(jwt, "tokenCacheService", cache);
        ReflectionTestUtils.setField(jwt, "userRepository", users);
        User user = new User(); user.setId(1L); user.setUsername("testuser"); user.setRole(User.Role.USER);
        when(users.findByUsername("testuser")).thenReturn(Optional.of(user));
        when(cache.isTokenValid(anyString())).thenReturn(true);
    }
    private String token(long remainingMs) {
        return Jwts.builder().setSubject("testuser").claim("tokenId", "dedicated-test-id")
            .setExpiration(new Date(System.currentTimeMillis() + remainingMs))
            .signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(SECRET)), SignatureAlgorithm.HS256).compact();
    }
    @Test void generatedAccessTokenKeepsOneHourTtl() {
        String token = jwt.generateToken("testuser"); assertTrue(jwt.validateToken(token));
        assertFalse(jwt.shouldRefreshToken(token)); assertEquals("testuser", jwt.extractUsernameFromToken(token));
    }
    @Test void validNearExpiryTokenCanRefresh() { assertTrue(jwt.shouldRefreshToken(token(60000))); assertNotNull(jwt.refreshToken(token(60000))); }
    @Test void graceWindowCanRefreshIssuedToken() { assertTrue(jwt.canRefreshExpiredToken(token(-60000))); assertNotNull(jwt.refreshToken(token(-60000))); }
    @Test void revokedExpiredTokenCannotRefresh() {
        when(cache.isTokenValid(anyString())).thenReturn(false);
        assertFalse(jwt.canRefreshExpiredToken(token(-60000))); verify(users, never()).findByUsername(anyString());
    }
    @Test void directRefreshRejectsRevokedToken() {
        when(cache.isTokenValid(anyString())).thenReturn(false);
        assertNull(jwt.refreshToken(token(60000))); assertNull(jwt.refreshToken(token(-60000)));
        verify(users, never()).findByUsername(anyString());
    }
    @Test void cacheFailureCannotIssueFreshToken() {
        when(cache.isTokenValid(anyString())).thenThrow(new IllegalStateException("dedicated cache failure"));
        assertFalse(jwt.canRefreshExpiredToken(token(-60000))); assertNull(jwt.refreshToken(token(-60000)));
        verify(users, never()).findByUsername(anyString());
    }
    @Test void cacheWriteFailureCannotIssueFreshToken() {
        doThrow(new IllegalStateException("dedicated cache failure")).when(cache).cacheToken(anyString(), anyString(), anyString(), anyLong());
        assertNull(jwt.refreshToken(token(-60000)));
    }
    @Test void outsideGraceWindowRejected() { assertFalse(jwt.canRefreshExpiredToken(token(-601000))); assertNull(jwt.refreshToken(token(-601000))); }
    @Test void invalidSignatureCannotRefresh() { assertFalse(jwt.canRefreshExpiredToken("dedicated.invalid.test")); assertNull(jwt.refreshToken("dedicated.invalid.test")); }
}
