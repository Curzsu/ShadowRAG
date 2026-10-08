package com.yizhaoqi.smartpai.observability;

import com.yizhaoqi.smartpai.model.Conversation;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.ConversationRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.service.ConversationService;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LangfuseChatSmokeIsolationTest {
    @Test void temporaryConversationDoesNotSwitchOrRebuildExistingWorkingMemory() {
        var smoke=new LangfuseChatSmokeTest();
        smoke.users=mock(UserRepository.class);
        smoke.conversations=mock(ConversationService.class);
        smoke.conversationRepository=mock(ConversationRepository.class);
        var user=new User(); user.setUsername("isolated-test");
        when(smoke.users.findByUsername("isolated-test")).thenReturn(Optional.of(user));
        when(smoke.conversationRepository.save(any(Conversation.class))).thenAnswer(call -> call.getArgument(0));
        var created=smoke.createIsolatedConversation("isolated-test");
        assertSame(user,created.getUser());
        assertNotNull(created.getConversationId());
        assertEquals("[]",created.getMessages());
        verify(smoke.conversationRepository).save(created);
        // Existing Redis working set and its version are untouched: no switch/rebuild call.
        verifyNoInteractions(smoke.conversations);
    }
}
