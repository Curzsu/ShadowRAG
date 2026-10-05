import { request } from '@/service/request';
import { cancelChatRequest, streamChat } from '@/service/api/chat-stream';
import { getAuthorization } from '@/service/request/shared';
import { getServiceBaseURL } from '@/utils/service';
import { createChatSession, createSendGate } from './chat-session';
import type { RequestIdentity } from './chat-session';
import { createChatViewLifecycle, draftAfterSubmission } from './chat-view';

export const useChatStore = defineStore(SetupStoreId.Chat, () => {
  const conversationId = ref('');
  const input = ref<Api.Chat.Input>({ message: '' });
  const list = ref<Api.Chat.Message[]>([]);
  const conversationList = ref<Api.Chat.Conversation[]>([]);
  const activeRequest = shallowRef<RequestIdentity>();
  const preparing = ref(false);
  const loadingHistory = ref(false);
  const scrollToBottom = ref<null | (() => void)>(null);
  const auth = useAuthStore();
  const gate = createSendGate();
  let listEpoch = 0;
  const view = createChatViewLifecycle({
    getLoginEpoch: auth.getLoginEpoch,
    isLoggedIn: () => auth.isLogin,
    onHistoryLoading: value => {
      loadingHistory.value = value;
    }
  });
  const { baseURL } = getServiceBaseURL(
    import.meta.env,
    import.meta.env.DEV && import.meta.env.VITE_HTTP_PROXY === 'Y'
  );
  function findMessage(identity: RequestIdentity) {
    return list.value.find(
      message =>
        message.messageId === identity.assistantMessageId &&
        message.requestId === identity.requestId &&
        message.conversationId === identity.conversationId
    );
  }
  const session = createChatSession({
    baseURL,
    getAuthorization,
    getLoginEpoch: auth.getLoginEpoch,
    createController: () => new AbortController(),
    streamChat,
    cancelChatRequest,
    onActive: identity => {
      activeRequest.value = identity;
    },
    onEvent(identity, event) {
      const message = findMessage(identity);
      if (!message) return;
      if (event.type === 'chunk') message.content += String(event.data.chunk);
      if (event.type === 'tool_progress')
        message.toolProgress = event.data.status === 'started' ? '正在检索知识库' : '知识库检索完成';
      scrollToBottom.value?.();
    },
    onStatus(identity, status, reason) {
      const message = findMessage(identity);
      if (!message) return;
      message.status = status;
      message.errorReason = reason;
      if (status === 'finished') {
        message.toolProgress = undefined;
        fetchConversationList();
      }
    },
    onClearActive: identity => {
      if (activeRequest.value?.requestId === identity.requestId) activeRequest.value = undefined;
    },
    onNewToken: auth.setToken,
    onAuthFailure: () => {
      auth.resetStore();
    }
  });
  function snapshot() {
    return view.capture();
  }
  function disposeActiveRequest(reason: 'switch' | 'delete' | 'logout' | 'dispose') {
    view.invalidate();
    listEpoch += 1;
    gate.invalidate();
    preparing.value = false;
    session.detach(reason);
    if (reason === 'logout') {
      conversationId.value = '';
      conversationList.value = [];
      list.value = [];
      input.value.message = '';
    }
  }
  async function fetchConversationList() {
    const current = snapshot();
    listEpoch += 1;
    const operation = listEpoch;
    const { error, data } = await request<Api.Chat.Conversation[]>({ url: 'chat/conversation/list' });
    if (current() && operation === listEpoch && !error && data) conversationList.value = data;
  }
  async function createInCurrentView(current: () => boolean) {
    await view.createConversation(
      () => request<{ conversationId: string; title: string }>({ url: 'chat/conversation/new', method: 'post' }),
      ({ error, data }) => {
        if (current() && !error && data) {
          conversationId.value = data.conversationId;
          list.value = [];
          fetchConversationList();
        }
      }
    );
  }
  async function createConversation() {
    disposeActiveRequest('switch');
    conversationId.value = '';
    list.value = [];
    await createInCurrentView(snapshot());
  }
  async function switchConversation(convId: string) {
    disposeActiveRequest('switch');
    conversationId.value = convId;
    list.value = [];
    await view.loadHistory(
      () =>
        request<{ conversationId: string; messages: { role: string; content: string; timestamp?: string }[] }>({
          url: `chat/conversation/${convId}/switch`,
          method: 'post'
        }),
      ({ error, data }) => {
        if (error || !data) return;
        conversationId.value = data.conversationId;
        list.value = (data.messages || []).map(message => ({
          ...message,
          role: message.role as 'user' | 'assistant',
          messageId: crypto.randomUUID(),
          conversationId: data.conversationId,
          status: 'finished'
        }));
      }
    );
  }
  async function deleteConversation(convId: string) {
    if (conversationId.value === convId) {
      disposeActiveRequest('delete');
      conversationId.value = '';
      list.value = [];
    }
    const current = snapshot();
    const { error } = await request({ url: `chat/conversation/${convId}`, method: 'delete' });
    if (current() && !error) await fetchConversationList();
  }
  async function ensureConversation(current = snapshot()) {
    if (!conversationId.value) await createInCurrentView(current);
  }
  async function sendMessage() {
    const message = input.value.message;
    if (
      activeRequest.value ||
      preparing.value ||
      !view.canSend() ||
      !auth.isLogin ||
      !message.trim() ||
      message.length > 16000
    )
      return;
    const current = snapshot();
    preparing.value = true;
    try {
      await gate.run(
        current,
        () => ensureConversation(current),
        async () => {
          if (!conversationId.value) return;
          const requestId = crypto.randomUUID();
          const assistantMessageId = crypto.randomUUID();
          list.value.push(
            {
              messageId: crypto.randomUUID(),
              requestId,
              conversationId: conversationId.value,
              role: 'user',
              content: message
            },
            {
              messageId: assistantMessageId,
              requestId,
              conversationId: conversationId.value,
              role: 'assistant',
              content: '',
              status: 'pending'
            }
          );
          input.value.message = draftAfterSubmission(message, input.value.message);
          preparing.value = false;
          await session.send({ requestId, conversationId: conversationId.value, message }, assistantMessageId);
        }
      );
    } finally {
      if (current()) preparing.value = false;
    }
  }
  async function stopGeneration() {
    if (preparing.value) disposeActiveRequest('dispose');
    else await session.stop();
  }
  return {
    input,
    conversationId,
    conversationList,
    list,
    activeRequest,
    preparing,
    loadingHistory,
    scrollToBottom,
    fetchConversationList,
    createConversation,
    switchConversation,
    deleteConversation,
    ensureConversation,
    sendMessage,
    stopGeneration,
    disposeActiveRequest
  };
});
