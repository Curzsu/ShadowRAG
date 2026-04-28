import { useWebSocket } from '@vueuse/core';
import { request } from '@/service/request';

export const useChatStore = defineStore(SetupStoreId.Chat, () => {
  const conversationId = ref<string>('');
  const input = ref<Api.Chat.Input>({ message: '' });

  const list = ref<Api.Chat.Message[]>([]);
  const conversationList = ref<Api.Chat.Conversation[]>([]);

  const store = useAuthStore();

  const {
    status: wsStatus,
    data: wsData,
    send: wsSend,
    open: wsOpen,
    close: wsClose
  } = useWebSocket(`/proxy-ws/chat/${store.token}`, {
    autoReconnect: true
  });

  const scrollToBottom = ref<null | (() => void)>(null);

  /** 获取会话列表 */
  async function fetchConversationList() {
    const { error, data } = await request<Api.Chat.Conversation[]>({
      url: 'chat/conversation/list'
    });
    if (!error && data) {
      conversationList.value = data;
    }
  }

  /** 新建对话 */
  async function createConversation() {
    const { error, data } = await request<{ conversationId: string; title: string }>({
      url: 'chat/conversation/new',
      method: 'post'
    });
    if (!error && data) {
      conversationId.value = data.conversationId;
      list.value = [];
      await fetchConversationList();
    }
  }

  /** 切换到指定会话 */
  async function switchConversation(convId: string) {
    const { error, data } = await request<{
      conversationId: string;
      messages: { role: string; content: string; timestamp?: string }[]
    }>({
      url: `chat/conversation/${convId}/switch`,
      method: 'post'
    });
    if (!error && data) {
      conversationId.value = data.conversationId;
      list.value = (data.messages || []).map(m => ({
        role: m.role as 'user' | 'assistant',
        content: m.content,
        timestamp: m.timestamp
      }));
    }
  }

  /** 删除会话 */
  async function deleteConversation(convId: string) {
    const { error } = await request({
      url: `chat/conversation/${convId}`,
      method: 'delete'
    });
    if (!error) {
      await fetchConversationList();
      // 如果删除的是当前会话，清空
      if (conversationId.value === convId) {
        conversationId.value = '';
        list.value = [];
      }
    }
  }

  /** 确保有活跃会话（如果没有则自动创建） */
  async function ensureConversation() {
    if (!conversationId.value) {
      await createConversation();
    }
  }

  return {
    input,
    conversationId,
    conversationList,
    list,
    wsStatus,
    wsData,
    wsSend,
    wsOpen,
    wsClose,
    scrollToBottom,
    fetchConversationList,
    createConversation,
    switchConversation,
    deleteConversation,
    ensureConversation
  };
});
