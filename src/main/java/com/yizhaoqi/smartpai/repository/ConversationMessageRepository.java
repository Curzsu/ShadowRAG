package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.ConversationMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, Long> {

    List<ConversationMessage> findByConversationConversationIdOrderByIdAsc(String conversationId);

    long deleteByConversationConversationId(String conversationId);
}
