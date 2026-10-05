package com.yizhaoqi.smartpai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Token缓存服务
 * 基于Redis实现JWT token的状态管理
 */
@Service
public class TokenCacheService {
    
    private static final Logger logger = LoggerFactory.getLogger(TokenCacheService.class);
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    // Redis key前缀
    private static final String TOKEN_PREFIX = "jwt:valid:";
    private static final String USER_TOKENS_PREFIX = "jwt:user:";
    private static final String REFRESH_PREFIX = "jwt:refresh:";
    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";
    private static final long ACCESS_GRACE_MS = 600000;
    
    /**
     * 缓存有效token信息
     */
    public void cacheToken(String tokenId, String userId, String username, long expireTimeMs) {
        try {
            String key = TOKEN_PREFIX + tokenId;
            Map<String, Object> tokenInfo = new HashMap<>();
            tokenInfo.put("userId", userId);
            tokenInfo.put("username", username);
            tokenInfo.put("expireTime", expireTimeMs);
            
            // 计算Redis过期时间（比JWT过期时间稍长一点）
            long ttlMs = accessRecordTtl(expireTimeMs);
            
            redisTemplate.opsForValue().set(key, tokenInfo, ttlMs, TimeUnit.MILLISECONDS);
            
            // 同时添加到用户token集合中
            addTokenToUser(userId, tokenId, expireTimeMs);
            
            logger.debug("Token cached: {} for user: {}", tokenId, username);
        } catch (Exception e) {
            logger.error("Failed to cache token: {}", tokenId, e);
            throw new IllegalStateException("Token cache unavailable", e);
        }
    }
    
    /**
     * 缓存refresh token
     */
    public void cacheRefreshToken(String refreshTokenId, String userId, String tokenId, long expireTimeMs) {
        try {
            String key = REFRESH_PREFIX + refreshTokenId;
            Map<String, Object> refreshInfo = new HashMap<>();
            refreshInfo.put("userId", userId);
            refreshInfo.put("tokenId", tokenId);
            refreshInfo.put("expireTime", expireTimeMs);
            
            long ttlSeconds = (expireTimeMs - System.currentTimeMillis()) / 1000;
            redisTemplate.opsForValue().set(key, refreshInfo, ttlSeconds, TimeUnit.SECONDS);
            
            logger.debug("Refresh token cached: {} for user: {}", refreshTokenId, userId);
        } catch (Exception e) {
            logger.error("Failed to cache refresh token: {}", refreshTokenId, e);
            throw new IllegalStateException("Token cache unavailable", e);
        }
    }
    
    /**
     * 验证token是否有效（未被拉黑且存在于缓存中）
     */
    public boolean isTokenValid(String tokenId) {
        try {
            // 先检查是否在黑名单中
            if (isTokenBlacklisted(tokenId)) {
                return false;
            }
            
            // 检查缓存中是否存在
            String key = TOKEN_PREFIX + tokenId;
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            logger.error("Failed to check token validity: {}", tokenId, e);
            return false;
        }
    }
    
    /**
     * 从缓存中获取token信息
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getTokenInfo(String tokenId) {
        try {
            String key = TOKEN_PREFIX + tokenId;
            Object tokenInfo = redisTemplate.opsForValue().get(key);
            return tokenInfo != null ? (Map<String, Object>) tokenInfo : null;
        } catch (Exception e) {
            logger.error("Failed to get token info: {}", tokenId, e);
            return null;
        }
    }
    
    /**
     * 验证refresh token是否有效
     */
    public boolean isRefreshTokenValid(String refreshTokenId) {
        try {
            String key = REFRESH_PREFIX + refreshTokenId;
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            logger.error("Failed to check refresh token validity: {}", refreshTokenId, e);
            return false;
        }
    }
    
    /**
     * 获取refresh token信息
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getRefreshTokenInfo(String refreshTokenId) {
        try {
            String key = REFRESH_PREFIX + refreshTokenId;
            Object refreshInfo = redisTemplate.opsForValue().get(key);
            return refreshInfo != null ? (Map<String, Object>) refreshInfo : null;
        } catch (Exception e) {
            logger.error("Failed to get refresh token info: {}", refreshTokenId, e);
            return null;
        }
    }
    
    /**
     * 将token加入黑名单（主动失效）
     */
    public void blacklistToken(String tokenId, long expireTimeMs) {
        try {
            String key = BLACKLIST_PREFIX + tokenId;
            long ttlMs = accessRecordTtl(expireTimeMs);
            
            if (ttlMs > 0) {
                redisTemplate.opsForValue().set(key, System.currentTimeMillis(), ttlMs, TimeUnit.MILLISECONDS);
                logger.debug("Token blacklisted: {}", tokenId);
            }
        } catch (Exception e) {
            logger.error("Failed to blacklist token: {}", tokenId, e);
            throw new IllegalStateException("Token revocation unavailable", e);
        }
    }
    
    /**
     * 检查token是否在黑名单中
     */
    public boolean isTokenBlacklisted(String tokenId) {
        try {
            String key = BLACKLIST_PREFIX + tokenId;
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            logger.error("Failed to check token blacklist: {}", tokenId, e);
            return true;
        }
    }
    
    /**
     * 移除token缓存
     */
    public void removeToken(String tokenId, String userId) {
        try {
            // 从有效token缓存中移除
            redisTemplate.delete(TOKEN_PREFIX + tokenId);
            
            // 从用户token集合中移除
            if (userId != null) {
                removeTokenFromUser(userId, tokenId);
            }
            
            logger.debug("Token removed from cache: {}", tokenId);
        } catch (Exception e) {
            logger.error("Failed to remove token: {}", tokenId, e);
            throw new IllegalStateException("Token revocation unavailable", e);
        }
    }
    
    /**
     * 移除用户的所有token（批量登出）
     */
    public void removeAllUserTokens(String userId) {
        try {
            String userTokenKey = USER_TOKENS_PREFIX + userId + ":tokens";
            Set<Object> tokenIds = redisTemplate.opsForSet().members(userTokenKey);
            
            if (tokenIds != null && !tokenIds.isEmpty()) {
                for (Object tokenId : tokenIds) {
                    removeToken(tokenId.toString(), null);
                }
            }
            
            // 清空用户token集合
            redisTemplate.delete(userTokenKey);
            
            logger.info("All tokens removed for user: {}", userId);
        } catch (Exception e) {
            logger.error("Failed to remove all user tokens: {}", userId, e);
            throw new IllegalStateException("Token revocation unavailable", e);
        }
    }
    
    /**
     * 添加token到用户集合
     */
    private synchronized void addTokenToUser(String userId, String tokenId, long expireTimeMs) {
        try {
            String key = USER_TOKENS_PREFIX + userId + ":tokens";
            redisTemplate.opsForSet().add(key, tokenId);
            
            // 设置过期时间
            long ttlMs = accessRecordTtl(expireTimeMs);
            Long existingTtlMs = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
            // Single-instance synchronized updates prevent an older token shortening this set.
            if (existingTtlMs == null || existingTtlMs < ttlMs) {
                redisTemplate.expire(key, Duration.ofMillis(ttlMs));
            }
        } catch (Exception e) {
            logger.error("Failed to add token to user set: {} - {}", userId, tokenId, e);
            throw new IllegalStateException("Token cache unavailable", e);
        }
    }
    
    /**
     * 从用户集合中移除token
     */
    private void removeTokenFromUser(String userId, String tokenId) {
        try {
            String key = USER_TOKENS_PREFIX + userId + ":tokens";
            redisTemplate.opsForSet().remove(key, tokenId);
        } catch (Exception e) {
            logger.error("Failed to remove token from user set: {} - {}", userId, tokenId, e);
            throw new IllegalStateException("Token revocation unavailable", e);
        }
    }
    
    /**
     * 获取用户的活跃token数量
     */
    public long getUserActiveTokenCount(String userId) {
        try {
            String key = USER_TOKENS_PREFIX + userId + ":tokens";
            Long count = redisTemplate.opsForSet().size(key);
            return count != null ? count : 0;
        } catch (Exception e) {
            logger.error("Failed to get user active token count: {}", userId, e);
            return 0;
        }
    }

    private long accessRecordTtl(long expireTimeMs) {
        return Math.max(1, expireTimeMs + ACCESS_GRACE_MS - System.currentTimeMillis());
    }
}
