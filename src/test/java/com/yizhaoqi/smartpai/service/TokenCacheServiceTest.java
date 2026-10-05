package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TokenCacheServiceTest {
    private TokenCacheService service;
    private RedisTemplate<String,Object> redis;
    private ValueOperations<String,Object> values;
    private SetOperations<String,Object> sets;
    @SuppressWarnings("unchecked") @BeforeEach void setup() {
        service = new TokenCacheService(); redis = mock(RedisTemplate.class); values = mock(ValueOperations.class); sets = mock(SetOperations.class);
        ReflectionTestUtils.setField(service,"redisTemplate",redis);
        when(redis.opsForValue()).thenReturn(values); when(redis.opsForSet()).thenReturn(sets);
    }
    @Test void graceWindowCacheLivesUntilExpPlusTenMinutes() {
        service.cacheToken("test-id","1","alice",System.currentTimeMillis()+60000);
        ArgumentCaptor<Long> ttl=ArgumentCaptor.forClass(Long.class);
        verify(values).set(eq("jwt:valid:test-id"),any(),ttl.capture(),eq(TimeUnit.MILLISECONDS));
        assertTrue(ttl.getValue() >= 659000 && ttl.getValue() <= 660000);
        ArgumentCaptor<Duration> setTtl=ArgumentCaptor.forClass(Duration.class);
        verify(redis).expire(eq("jwt:user:1:tokens"),setTtl.capture()); assertTrue(setTtl.getValue().toMillis()>=659000);
    }
    @Test void blacklistLivesBeyondJwtExpiry() {
        service.blacklistToken("test-id",System.currentTimeMillis()-60000);
        ArgumentCaptor<Long> ttl=ArgumentCaptor.forClass(Long.class);
        verify(values).set(eq("jwt:blacklist:test-id"),any(),ttl.capture(),eq(TimeUnit.MILLISECONDS)); assertTrue(ttl.getValue()>=539000);
    }
    @Test void blacklistLookupFailureFailsClosed() {
        when(redis.hasKey("jwt:blacklist:test-id")).thenThrow(new IllegalStateException("test"));
        when(redis.hasKey("jwt:valid:test-id")).thenReturn(true);
        assertFalse(service.isTokenValid("test-id")); assertTrue(service.isTokenBlacklisted("test-id"));
    }
    @Test void cacheWriteFailurePropagates() {
        doThrow(new IllegalStateException("test")).when(values).set(anyString(),any(),anyLong(),any(TimeUnit.class));
        assertThrows(IllegalStateException.class,()->service.cacheToken("test-id","1","alice",System.currentTimeMillis()+60000));
    }
    @Test void addingOlderTokenDoesNotShortenUserSetLifetime() {
        when(redis.getExpire("jwt:user:1:tokens",TimeUnit.MILLISECONDS)).thenReturn(1000000L);
        service.cacheToken("test-id","1","alice",System.currentTimeMillis()+60000);
        verify(redis,never()).expire(eq("jwt:user:1:tokens"),any(Duration.class));
    }
}
