package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.model.OrganizationTag;
import com.yizhaoqi.smartpai.repository.OrganizationTagRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.utils.PasswordUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/**
 * UserService 的测试类
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {
    // 模拟 UserRepository 实例
    @Mock
    private UserRepository userRepository;

    @Mock
    private OrganizationTagRepository organizationTagRepository;

    @Mock
    private OrgTagCacheService orgTagCacheService;

    // 注入模拟的 UserService 实例
    @InjectMocks
    private UserService userService;

    /**
     * 测试用户注册成功的情况
     */
    @Test
    void testRegisterUser_Success() {
        // 假设用户名 "testuser" 在数据库中不存在
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.empty());
        when(organizationTagRepository.existsByTagId("DEFAULT")).thenReturn(true);

        // 调用 userService 的 registerUser 方法进行用户注册
        userService.registerUser("testuser", "password123");

        // 创建 ArgumentCaptor 来捕获 save 方法的参数
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        // 先保存用户，再保存私人组织标签分配后的用户，并捕获参数
        verify(userRepository, times(2)).save(userCaptor.capture());

        // 获取捕获的 User 对象并进行断言
        User savedUser = userCaptor.getValue();
        assertNotNull(savedUser);
        assertEquals("testuser", savedUser.getUsername());
        assertTrue(PasswordUtil.matches("password123", savedUser.getPassword()));
        assertEquals(User.Role.USER, savedUser.getRole());
        assertEquals("PRIVATE_testuser", savedUser.getPrimaryOrg());
        assertEquals("PRIVATE_testuser", savedUser.getOrgTags());
        ArgumentCaptor<OrganizationTag> tagCaptor = ArgumentCaptor.forClass(OrganizationTag.class);
        verify(organizationTagRepository).save(tagCaptor.capture());
        assertEquals("PRIVATE_testuser", tagCaptor.getValue().getTagId());
        assertSame(savedUser, tagCaptor.getValue().getCreatedBy());
        verify(orgTagCacheService).cacheUserOrgTags("testuser", List.of("PRIVATE_testuser"));
        verify(orgTagCacheService).cacheUserPrimaryOrg("testuser", "PRIVATE_testuser");
    }

    /**
     * 测试用户注册时用户名已存在的情况
     */
    @Test
    void testRegisterUser_UsernameExists() {
        // 假设用户名 "testuser" 在数据库中已存在
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(new User()));

        // 断言在注册已存在的用户名时抛出 CustomException 异常
        CustomException exception = assertThrows(CustomException.class, () -> userService.registerUser("testuser", "password123"));
        assertEquals("Username already exists", exception.getMessage());
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    /**
     * 测试用户认证成功的情况
     */
   @Test
void testAuthenticateUser_Success() {
    // 使用 PasswordUtil 生成加密密码
    String rawPassword = "password123";
    String encodedPassword = PasswordUtil.encode(rawPassword);

    // 创建一个带有加密密码的用户对象，并确保设置了用户名
    User user = new User();
    user.setUsername("testuser"); // 确保设置了用户名
    user.setPassword(encodedPassword);

    when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

    try {
        // 调用 userService 的 authenticateUser 方法进行用户认证
        String username = userService.authenticateUser("testuser", rawPassword);

        // 打印返回的用户名（仅用于调试）
        System.out.println("Returned username: " + username);

        // 断言返回的用户名是否正确
        assertEquals("testuser", username);
    } catch (CustomException e) {
        // 捕获并打印异常信息
        System.out.println("Exception message: " + e.getMessage());
        throw e;
    }

    // 打印实际的加密密码（仅用于调试）
    System.out.println("Actual encrypted password: " + user.getPassword());
}





    /**
     * 测试用户认证失败的情况
     */
    @Test
    void testAuthenticateUser_InvalidCredentials() {
        // 假设用户名 "testuser" 在数据库中不存在
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.empty());

        // 断言在使用错误密码认证时抛出 CustomException 异常
        CustomException exception = assertThrows(CustomException.class, () -> userService.authenticateUser("testuser", "wrongpassword"));
        assertEquals("Invalid username or password", exception.getMessage());
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
    }
}
