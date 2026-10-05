import { ChatStreamError, canonicalizeChatInput } from '../../../service/api/chat-stream';
import type {
  ChatCancelOptions,
  ChatEventEnvelope,
  ChatStreamInput,
  ChatTerminalStatus,
  ChatTransportOptions
} from '../../../service/api/chat-stream';

export type MessageStatus = 'pending' | 'loading' | 'cancelling' | 'finished' | 'cancelled' | 'error';
export interface RequestIdentity {
  requestId: string;
  conversationId: string;
  assistantMessageId: string;
  controller: AbortController;
  lastSeq: number;
}
export interface ChatSessionDependencies {
  baseURL: string;
  getAuthorization: () => string | null;
  getLoginEpoch: () => number;
  createController: () => AbortController;
  streamChat: (input: ChatStreamInput, options: ChatTransportOptions) => Promise<{ status: ChatTerminalStatus }>;
  cancelChatRequest: (requestId: string, options: ChatCancelOptions) => Promise<{ requestId: string; status: string }>;
  onActive: (identity: RequestIdentity) => void;
  onEvent: (identity: RequestIdentity, event: ChatEventEnvelope) => void;
  onStatus: (identity: RequestIdentity, status: MessageStatus, reason?: string) => void;
  onClearActive: (identity: RequestIdentity) => void;
  onNewToken: (token: string) => void;
  onAuthFailure?: () => void;
}
export interface ChatSession {
  send(input: ChatStreamInput, assistantMessageId: string): Promise<void>;
  stop(): Promise<void>;
  detach(reason: 'switch' | 'delete' | 'logout' | 'dispose'): void;
}
export function createChatSession(deps: ChatSessionDependencies): ChatSession {
  let active:
    | (RequestIdentity & { epoch: number; authorization: string | null; error?: string; stopping?: boolean })
    | undefined;
  const current = (identity: typeof active) =>
    Boolean(identity && active === identity && identity.epoch === deps.getLoginEpoch());
  function clear(identity: NonNullable<typeof active>) {
    if (!current(identity)) return;
    active = undefined;
    deps.onClearActive(identity);
  }
  function cancelOptions(identity: NonNullable<typeof active>): ChatCancelOptions {
    const authorization = deps.getAuthorization();
    return {
      baseURL: deps.baseURL,
      getAuthorization: () => authorization,
      signal: deps.createController().signal,
      onNewToken: token => {
        if (current(identity)) deps.onNewToken(token);
      }
    };
  }
  function terminal(identity: NonNullable<typeof active>, status: string) {
    if (!current(identity)) return;
    if (status === 'finished' || status === 'cancelled') deps.onStatus(identity, status);
    else deps.onStatus(identity, 'error', identity.error || (status === 'timed_out' ? '生成超时' : '生成失败'));
  }
  return {
    async send(input, assistantMessageId) {
      if (active) return;
      const command = canonicalizeChatInput(input);
      const identity = {
        ...command,
        assistantMessageId,
        controller: deps.createController(),
        lastSeq: 0,
        epoch: deps.getLoginEpoch(),
        authorization: deps.getAuthorization(),
        error: undefined as string | undefined,
        stopping: false
      };
      active = identity;
      deps.onActive(identity);
      deps.onStatus(identity, 'pending');
      try {
        const result = await deps.streamChat(command, {
          ...cancelOptions(identity),
          signal: identity.controller.signal,
          getAuthorization: () => (current(identity) ? deps.getAuthorization() : identity.authorization),
          onEvent(event) {
            if (
              !current(identity) ||
              event.requestId !== identity.requestId ||
              event.conversationId !== identity.conversationId ||
              event.seq <= identity.lastSeq
            )
              return;
            // Transport validates continuity, including unknown types withheld from this callback.
            identity.lastSeq = event.seq;
            if (event.type === 'error') {
              identity.error = String(event.data.message);
              deps.onStatus(identity, 'error', identity.error);
            } else if (event.type === 'chunk' && !identity.stopping) deps.onStatus(identity, 'loading');
            deps.onEvent(identity, event);
          }
        });
        terminal(identity, result.status);
      } catch (error) {
        if (current(identity)) {
          let reason = '连接中断，请重新发送';
          if (error instanceof ChatStreamError) {
            if (error.kind === 'protocol') reason = '响应格式异常，请重新发送';
            else if (error.kind === 'http') reason = error.status === 401 ? '登录已失效，请重新登录' : error.message;
          }
          deps.onStatus(identity, 'error', identity.error || reason);
          if (error instanceof ChatStreamError && error.status === 401) deps.onAuthFailure?.();
        }
      } finally {
        clear(identity);
      }
    },
    async stop() {
      const identity = active;
      if (!identity || !current(identity) || identity.stopping) return;
      identity.stopping = true;
      deps.onStatus(identity, 'cancelling');
      try {
        const result = await deps.cancelChatRequest(identity.requestId, cancelOptions(identity));
        if (!current(identity)) return;
        if (result.status === 'completing' || result.status === 'finished') {
          identity.stopping = false;
          deps.onStatus(identity, 'loading');
          return;
        }
        terminal(identity, result.status);
      } catch {
        if (current(identity)) deps.onStatus(identity, 'error', '连接已关闭，停止结果未确认');
      }
      if (current(identity)) {
        clear(identity);
        identity.controller.abort();
      }
    },
    detach() {
      const identity = active;
      if (!identity) return;
      const options = cancelOptions(identity);
      // Revoke all UI/token callbacks before cancellation or abort can complete.
      active = undefined;
      deps.onStatus(identity, 'cancelled', '已停止');
      deps.onClearActive(identity);
      deps.cancelChatRequest(identity.requestId, options).catch(() => {});
      identity.controller.abort();
    }
  };
}

export function createSendGate() {
  let active: object | undefined;
  return {
    async run(isCurrent: () => boolean, ensure: () => Promise<void>, send: () => Promise<void>) {
      if (active) return;
      const identity = {};
      active = identity;
      try {
        await ensure();
        if (active === identity && isCurrent()) await send();
      } finally {
        if (active === identity) active = undefined;
      }
    },
    invalidate() {
      active = undefined;
    }
  };
}
