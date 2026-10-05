package com.yizhaoqi.smartpai.support;

import com.yizhaoqi.smartpai.config.*;
import com.yizhaoqi.smartpai.controller.ChatController;
import com.yizhaoqi.smartpai.controller.ChatStreamingExceptionHandler;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.service.ChatHandler;
import com.yizhaoqi.smartpai.service.ConversationService;
import com.yizhaoqi.smartpai.service.CustomUserDetailsService;
import com.yizhaoqi.smartpai.service.TokenCacheService;
import com.yizhaoqi.smartpai.service.chat.ChatRequestRegistry;
import com.yizhaoqi.smartpai.service.chat.ChatStreamService;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.dao.PersistenceExceptionTranslationAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.core.userdetails.User;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Explicit embedded-Servlet fixture with real security and no external infrastructure. */
@TestComponent
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
        RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class,
        PersistenceExceptionTranslationAutoConfiguration.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, OrgTagAuthorizationFilter.class, JwtUtils.class,
        ChatController.class, ChatStreamingExceptionHandler.class, ChatStreamService.class, ChatStreamingConfig.class,
        ChatStreamingProperties.class, ChatRequestRegistry.class})
public class ChatStreamingTestApplication {
    @Bean
    ChatHandler chatHandler() { return mock(ChatHandler.class); }

    @Bean
    ConversationService conversationService() { return mock(ConversationService.class); }

    @Bean
    TokenCacheService tokenCacheService() {
        TokenCacheService cache = mock(TokenCacheService.class);
        when(cache.isTokenValid(anyString())).thenReturn(true);
        return cache;
    }

    @Bean
    CustomUserDetailsService customUserDetailsService() {
        var details = mock(CustomUserDetailsService.class);
        when(details.loadUserByUsername(anyString())).thenAnswer(invocation ->
                User.withUsername(invocation.getArgument(0)).password("test").roles("USER").build());
        return details;
    }

    @Bean
    UserRepository userRepository() {
        UserRepository repository = mock(UserRepository.class);
        when(repository.findByUsername(anyString())).thenAnswer(invocation -> {
            var user = new com.yizhaoqi.smartpai.model.User();
            user.setUsername(invocation.getArgument(0));
            user.setId(1L);
            user.setRole(com.yizhaoqi.smartpai.model.User.Role.USER);
            return Optional.of(user);
        });
        return repository;
    }

    @Bean
    FileUploadRepository fileUploadRepository() { return mock(FileUploadRepository.class); }

    @SuppressWarnings("unchecked")
    @Bean
    RedisTemplate<String, Object> redisTemplate() { return mock(RedisTemplate.class); }
}
