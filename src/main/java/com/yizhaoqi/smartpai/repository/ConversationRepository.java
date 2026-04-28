package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    /**
     * 根据用户ID查询所有会话，按更新时间降序排列
     */
    List<Conversation> findByUserIdOrderByUpdatedAtDesc(Long userId);

    /**
     * 根据会话UUID查询会话
     */
    Optional<Conversation> findByConversationId(String conversationId);

    /**
     * 根据会话UUID删除会话
     */
    void deleteByConversationId(String conversationId);
}
