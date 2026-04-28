package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.model.Conversation;
import com.yizhaoqi.smartpai.service.ConversationService;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import com.yizhaoqi.smartpai.utils.LogUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 会话管理控制器
 * 提供新建、列表、切换、删除会话的 REST 接口
 */
@RestController
@RequestMapping("/api/v1/chat/conversation")
public class ConversationController {

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private JwtUtils jwtUtils;

    /**
     * 从请求中提取用户名（从JWT token的sub字段）
     */
    private String extractUsername(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            String token = bearerToken.substring(7);
            return jwtUtils.extractUsernameFromToken(token);
        }
        return null;
    }

    /**
     * 新建对话
     */
    @PostMapping("/new")
    public ResponseEntity<?> createConversation(HttpServletRequest request) {
        String username = extractUsername(request);
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("CREATE_CONVERSATION");
        try {
            LogUtils.logBusiness("CREATE_CONVERSATION", username, "接收到新建对话请求");

            Conversation conversation = conversationService.createConversation(username);

            Map<String, Object> data = new HashMap<>();
            data.put("conversationId", conversation.getConversationId());
            data.put("title", conversation.getTitle());
            data.put("createdAt", conversation.getCreatedAt());

            monitor.end("新建对话成功");
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "新建对话成功");
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("CREATE_CONVERSATION", username, "新建对话失败", e);
            monitor.end("新建对话失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "新建对话失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 获取会话列表
     */
    @GetMapping("/list")
    public ResponseEntity<?> getConversationList(HttpServletRequest request) {
        String username = extractUsername(request);
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_CONVERSATION_LIST");
        try {
            LogUtils.logBusiness("GET_CONVERSATION_LIST", username, "接收到获取会话列表请求");

            List<Conversation> conversations = conversationService.getConversationList(username);

            List<Map<String, Object>> conversationData = conversations.stream().map(conv -> {
                Map<String, Object> item = new HashMap<>();
                item.put("conversationId", conv.getConversationId());
                item.put("title", conv.getTitle());
                item.put("updatedAt", conv.getUpdatedAt());
                item.put("createdAt", conv.getCreatedAt());
                return item;
            }).collect(Collectors.toList());

            monitor.end("获取会话列表成功");
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "获取会话列表成功");
            response.put("data", conversationData);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_CONVERSATION_LIST", username, "获取会话列表失败", e);
            monitor.end("获取会话列表失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "获取会话列表失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 切换到指定会话
     */
    @PostMapping("/{conversationId}/switch")
    public ResponseEntity<?> switchConversation(
            HttpServletRequest request,
            @PathVariable String conversationId) {
        String username = extractUsername(request);
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("SWITCH_CONVERSATION");
        try {
            LogUtils.logBusiness("SWITCH_CONVERSATION", username, "切换到会话: %s", conversationId);

            List<Map<String, String>> messages = conversationService.switchConversation(username, conversationId);

            monitor.end("切换会话成功");
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "切换会话成功");
            response.put("data", Map.of(
                "conversationId", conversationId,
                "messages", messages
            ));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("SWITCH_CONVERSATION", username, "切换会话失败: %s", e, conversationId);
            monitor.end("切换会话失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "切换会话失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 删除会话
     */
    @DeleteMapping("/{conversationId}")
    public ResponseEntity<?> deleteConversation(
            HttpServletRequest request,
            @PathVariable String conversationId) {
        String username = extractUsername(request);
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("DELETE_CONVERSATION");
        try {
            LogUtils.logBusiness("DELETE_CONVERSATION", username, "删除会话: %s", conversationId);

            conversationService.deleteConversation(username, conversationId);

            monitor.end("删除会话成功");
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "删除会话成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("DELETE_CONVERSATION", username, "删除会话失败: %s", e, conversationId);
            monitor.end("删除会话失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "删除会话失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
}
