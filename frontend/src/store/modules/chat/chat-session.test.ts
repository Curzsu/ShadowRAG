import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createRequire } from 'node:module';
import { AxiosError } from 'axios';
import { ChatStreamError, streamChat } from '../../../service/api/chat-stream';
import type { ChatTransportOptions } from '../../../service/api/chat-stream';
import { createAuthActions, createAuthResponseGuard, createLoginEpoch, refreshForLogin } from '../auth/auth-session';
import type { LoginCredentials, RefreshState } from '../auth/auth-session';
import { createChatSession, createSendGate } from './chat-session';
import type { MessageStatus, RequestIdentity } from './chat-session';
import { createChatViewLifecycle, draftAfterSubmission } from './chat-view';
const { createFlatRequest } = createRequire(import.meta.url)(
  '../../../../packages/axios/src/index.ts'
) as typeof import('../../../../packages/axios/src/index');

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
const input = {
  requestId: '11111111-1111-4111-8111-111111111111',
  conversationId: '22222222-2222-4222-8222-222222222222',
  message: '  原文\n'
};
function harness() {
  const epoch = createLoginEpoch();
  let authorization: string | null = 'Bearer dedicated-test';
  let active: RequestIdentity | undefined;
  let token = '';
  let cancellations = 0;
  let cancelAuthorization: string | null = null;
  let cancelStatus = 'cancelled';
  let cancelError = false;
  const statuses = new Map<string, { status: MessageStatus; reason?: string }>();
  const text = new Map<string, string>();
  const runs: { options: ChatTransportOptions; done: ReturnType<typeof deferred<{ status: 'finished' }>> }[] = [];
  const session = createChatSession({
    baseURL: '/api',
    getAuthorization: () => authorization,
    getLoginEpoch: epoch.current,
    createController: () => new AbortController(),
    streamChat: async (_input, options) => {
      const done = deferred<{ status: 'finished' }>();
      runs.push({ options, done });
      return done.promise;
    },
    cancelChatRequest: async (_id, options) => {
      cancellations += 1;
      cancelAuthorization = options.getAuthorization();
      if (cancelError) throw new Error('network');
      return { requestId: _id, status: cancelStatus };
    },
    onActive: identity => {
      active = identity;
      text.set(identity.assistantMessageId, '');
    },
    onEvent: (identity, event) => {
      if (event.type === 'chunk')
        text.set(identity.assistantMessageId, text.get(identity.assistantMessageId) + String(event.data.chunk));
    },
    onStatus: (identity, status, reason) => {
      statuses.set(identity.assistantMessageId, { status, reason });
    },
    onClearActive: () => {
      active = undefined;
    },
    onNewToken: value => {
      token = value;
    }
  });
  // Positional wire fixtures keep event order explicit in the race tests.
  // eslint-disable-next-line max-params
  const emit = (run: number, type: string, seq: number, data: Record<string, unknown>, ids = input) =>
    runs[run].options.onEvent({ ...ids, type, seq, data });
  return {
    session,
    runs,
    epoch,
    emit,
    statuses,
    text,
    get active() {
      return active;
    },
    get token() {
      return token;
    },
    get cancellations() {
      return cancellations;
    },
    get cancelAuthorization() {
      return cancelAuthorization;
    },
    set authorization(value: string | null) {
      authorization = value;
    },
    get authorization() {
      return authorization;
    },
    set cancelStatus(value: string) {
      cancelStatus = value;
    },
    get cancelStatus() {
      return cancelStatus;
    },
    set cancelError(value: boolean) {
      cancelError = value;
    },
    get cancelError() {
      return cancelError;
    }
  };
}

test('uppercase UUID input owns canonical events while wrong identities remain ignored', async () => {
  const canonical = {
    ...input,
    conversationId: 'abcdefab-cdef-4abc-8def-abcdefabcdef',
    requestId: 'abcd1234-cdef-4abc-8def-123456abcdef'
  };
  const uppercase = {
    ...canonical,
    conversationId: canonical.conversationId.toUpperCase(),
    requestId: canonical.requestId.toUpperCase()
  };
  const h = harness();
  const pending = h.session.send(uppercase, 'canonical-assistant');
  assert.equal(h.active?.conversationId, canonical.conversationId);
  assert.equal(h.active?.requestId, canonical.requestId);
  h.emit(0, 'chunk', 1, { chunk: 'owned answer' }, canonical);
  h.emit(0, 'chunk', 2, { chunk: 'foreign answer' }, { ...canonical, conversationId: input.conversationId });
  assert.equal(h.text.get('canonical-assistant'), 'owned answer');
  assert.equal(uppercase.conversationId, canonical.conversationId.toUpperCase());
  h.runs[0].done.resolve({ status: 'finished' });
  await pending;
});
test('fastResponseHasRegisteredAssistant', async () => {
  const h = harness();
  const pending = h.session.send(input, 'a');
  assert.equal(h.active?.assistantMessageId, 'a');
  h.emit(0, 'chunk', 1, { chunk: '快' });
  assert.equal(h.text.get('a'), '快');
  h.runs[0].done.resolve({ status: 'finished' });
  await pending;
});
test('doubleSendCreatesOneRequest', async () => {
  const h = harness();
  const pending = h.session.send(input, 'a');
  await h.session.send(input, 'b');
  assert.equal(h.runs.length, 1);
  h.runs[0].done.resolve({ status: 'finished' });
  await pending;
});
test('lateConversationCreationCannotStartChatAfterSwitch', async () => {
  const gate = createSendGate();
  const creation = deferred<undefined>();
  let current = true;
  let creations = 0;
  let sends = 0;
  const ensure = () => {
    creations += 1;
    return creation.promise;
  };
  const send = async () => {
    sends += 1;
  };
  const first = gate.run(() => current, ensure, send);
  await gate.run(() => current, ensure, send);
  assert.equal(creations, 1);
  current = false;
  gate.invalidate();
  creation.resolve(undefined);
  await first;
  assert.equal(sends, 0);
  current = true;
  await gate.run(
    () => current,
    async () => {},
    send
  );
  assert.equal(sends, 1);
});
test('oldEventCannotModifyNewConversation and oldFinallyCannotClearNewActive', async () => {
  const h = harness();
  const old = h.session.send(input, 'old');
  h.session.detach('switch');
  const nextInput = {
    ...input,
    requestId: '33333333-3333-4333-8333-333333333333',
    conversationId: '44444444-4444-4444-8444-444444444444'
  };
  const next = h.session.send(nextInput, 'new');
  h.emit(0, 'chunk', 1, { chunk: 'stale' });
  h.runs[0].done.resolve({ status: 'finished' });
  await old;
  assert.equal(h.active?.requestId, nextInput.requestId);
  assert.equal(h.text.get('new'), '');
  assert.equal(h.text.get('old'), '');
  h.runs[1].done.resolve({ status: 'finished' });
  await next;
});
test('logoutLateNewTokenCannotRestoreSession even after a new login', async () => {
  const h = harness();
  const old = h.session.send(input, 'a');
  h.session.detach('logout');
  h.epoch.advance();
  h.authorization = null;
  h.epoch.advance();
  h.authorization = 'Bearer new-test';
  h.runs[0].options.onNewToken?.('obsolete-test');
  assert.equal(h.token, '');
  assert.equal(h.cancelAuthorization, 'Bearer dedicated-test');
  h.runs[0].done.resolve({ status: 'finished' });
  await old;
});
test('cancelFailureStillClosesLocalStream', async () => {
  const h = harness();
  const pending = h.session.send(input, 'a');
  h.emit(0, 'chunk', 1, { chunk: '保留' });
  h.cancelError = true;
  await h.session.stop();
  assert.equal(h.runs[0].options.signal?.aborted, true);
  assert.equal(h.active, undefined);
  assert.equal(h.statuses.get('a')?.status, 'error');
  assert.match(h.statuses.get('a')?.reason || '', /未确认/);
  assert.equal(h.text.get('a'), '保留');
  h.runs[0].done.resolve({ status: 'finished' });
  await pending;
});

test('stop uses the latest refreshed credential and preserves partial text', async () => {
  const h = harness();
  const pending = h.session.send(input, 'a');
  h.emit(0, 'chunk', 1, { chunk: 'partial' });
  h.authorization = 'Bearer rotated-test';
  await h.session.stop();
  assert.equal(h.cancelAuthorization, 'Bearer rotated-test');
  assert.equal(h.text.get('a'), 'partial');
  assert.equal(h.statuses.get('a')?.status, 'cancelled');
  h.runs[0].done.resolve({ status: 'finished' });
  await pending;
});

test('protocol failures display a user-facing explanation without parser internals', async () => {
  const h = harness();
  const pending = h.session.send(input, 'a');
  h.runs[0].done.reject(new ChatStreamError('protocol', 'sequence gap internal detail'));
  await pending;
  assert.equal(h.statuses.get('a')?.reason, '响应格式异常，请重新发送');
});
test('completingCancelKeepsReadingOriginalStream', async () => {
  const h = harness();
  const pending = h.session.send(input, 'a');
  h.cancelStatus = 'completing';
  await h.session.stop();
  assert.equal(h.runs[0].options.signal?.aborted, false);
  assert.equal(h.statuses.get('a')?.status, 'loading');
  h.emit(0, 'chunk', 1, { chunk: 'done' });
  h.runs[0].done.resolve({ status: 'finished' });
  await pending;
  assert.equal(h.statuses.get('a')?.status, 'finished');
  assert.equal(h.runs.length, 1);
});
test('errorKeepsPartialTextVisible', async () => {
  const h = harness();
  const pending = h.session.send(input, 'a');
  h.emit(0, 'chunk', 1, { chunk: 'partial' });
  h.emit(0, 'error', 2, { code: 'MODEL_ERROR', message: '失败' });
  h.runs[0].done.reject(new Error('failed'));
  await pending;
  assert.equal(h.text.get('a'), 'partial');
  assert.equal(h.statuses.get('a')?.status, 'error');
  assert.equal(h.statuses.get('a')?.reason, '失败');
});
test('ordinary HTTP auth response guard rejects old token and auth effects after reset/new login', () => {
  const epoch = createLoginEpoch();
  const guard = createAuthResponseGuard(epoch.current);
  const config = {};
  guard.capture(config);
  assert.equal(guard.isCurrent(config), true);
  epoch.advance();
  epoch.advance();
  assert.equal(guard.isCurrent(config), false);
  assert.equal(guard.isCurrent(), false);
  const current = {};
  guard.capture(current);
  assert.equal(guard.isCurrent(current), true);
});

test('ordinary HTTP captures call-time epoch even when its interceptor runs after reset', () => {
  const epoch = createLoginEpoch();
  const guard = createAuthResponseGuard(epoch.current);
  const callEpoch = epoch.current();
  epoch.advance();
  const config = {};
  guard.capture(config, callEpoch);
  assert.equal(guard.isCurrent(config), false);
});
test('meta1 unknown2 chunk3 completion4 passes actual transport plus session', async () => {
  let content = '';
  let final = '';
  const wire = [
    ['meta', {}],
    ['future', {}],
    ['chunk', { chunk: '中文' }],
    ['completion', { status: 'finished' }]
  ]
    .map(
      ([type, data], index) =>
        `event: ${type}\nid: ${index + 1}\ndata: ${JSON.stringify({ ...input, type, seq: index + 1, data })}\n\n`
    )
    .join('');
  const session = createChatSession({
    baseURL: '/api',
    getAuthorization: () => null,
    getLoginEpoch: () => 1,
    createController: () => new AbortController(),
    streamChat: (value, options) =>
      streamChat(value, {
        ...options,
        fetchImpl: async () => new Response(wire, { headers: { 'Content-Type': 'text/event-stream' } })
      }),
    cancelChatRequest: async id => ({ requestId: id, status: 'cancelled' }),
    onActive: () => {},
    onEvent: (_identity, event) => {
      if (event.type === 'chunk') content += event.data.chunk;
    },
    onStatus: (_identity, status) => {
      final = status;
    },
    onClearActive: () => {},
    onNewToken: () => {}
  });
  await session.send(input, 'a');
  assert.equal(content, '中文');
  assert.equal(final, 'finished');
});

test('logout during user-info/loginByToken prevents stale user and token writes', async () => {
  const epoch = createLoginEpoch();
  const response = deferred<{ data: { name: string }; error: null }>();
  let token = '';
  let user = '';
  const actions = createAuthActions({
    getEpoch: epoch.current,
    fetchUserInfo: () => response.promise,
    applyUserInfo: info => {
      user = info.name;
    },
    saveCredentials: credentials => {
      token = credentials.token;
    },
    readToken: () => token,
    applyToken: value => {
      token = value;
    }
  });
  const pending = actions.loginByToken({ token: 'old-test', refreshToken: 'old-refresh-test' });
  epoch.advance();
  token = 'new-test';
  response.resolve({ data: { name: 'obsolete' }, error: null });
  assert.equal(await pending, false);
  assert.equal(user, '');
  assert.equal(token, 'new-test');
});

test('current login keeps a token refreshed while user info was pending', async () => {
  const response = deferred<{ data: { name: string }; error: null }>();
  let token = '';
  let applied = '';
  const actions = createAuthActions({
    getEpoch: () => 1,
    fetchUserInfo: () => response.promise,
    applyUserInfo: info => {
      applied = info.name;
    },
    saveCredentials: credentials => {
      token = credentials.token;
    },
    readToken: () => token,
    applyToken: value => {
      token = value;
    }
  });
  const pending = actions.loginByToken({ token: 'original-test', refreshToken: 'refresh-test' });
  token = 'refreshed-test';
  response.resolve({ data: { name: 'current' }, error: null });
  assert.equal(await pending, true);
  assert.equal(applied, 'current');
  assert.equal(token, 'refreshed-test');
});

test('refresh late success/failure cannot overwrite or reset a new login', async () => {
  await Promise.all(
    [false, true].map(async fails => {
      const epoch = createLoginEpoch();
      const pending = deferred<{ data: LoginCredentials | null; error: unknown }>();
      const state: RefreshState = { refreshTokenFn: null };
      let writes = 0;
      let resets = 0;
      const result = refreshForLogin(state, {
        getEpoch: epoch.current,
        readRefreshToken: () => 'refresh-test',
        fetchRefresh: () => pending.promise,
        applyCredentials: () => {
          writes += 1;
        },
        onFailure: () => {
          resets += 1;
        },
        scheduleCleanup: () => {}
      });
      epoch.advance();
      pending.resolve({
        data: fails ? null : { token: 'old-test', refreshToken: 'old-refresh' },
        error: fails ? new Error('expired') : null
      });
      assert.equal(await result, false);
      assert.equal(writes, 0);
      assert.equal(resets, 0);
    })
  );
});

test('new login refresh never joins old refresh or loses it to old cleanup', async () => {
  const epoch = createLoginEpoch();
  const state: RefreshState = { refreshTokenFn: null };
  const responses = [
    deferred<{ data: LoginCredentials; error: null }>(),
    deferred<{ data: LoginCredentials; error: null }>()
  ];
  const cleanup: (() => void)[] = [];
  let calls = 0;
  const deps = {
    getEpoch: epoch.current,
    readRefreshToken: () => 'refresh-test',
    fetchRefresh: () => {
      const response = responses[calls];
      calls += 1;
      return response.promise;
    },
    applyCredentials: () => {},
    onFailure: () => {},
    scheduleCleanup: (callback: () => void) => {
      cleanup.push(callback);
    }
  };
  const old = refreshForLogin(state, deps);
  responses[0].resolve({ data: { token: 'old-test', refreshToken: 'old-refresh' }, error: null });
  assert.equal(await old, true);
  epoch.advance();
  const next = refreshForLogin(state, deps);
  const currentPromise = state.refreshTokenFn;
  assert.equal(calls, 2);
  cleanup[0]();
  assert.equal(state.refreshTokenFn, currentPromise);
  const concurrent = refreshForLogin(state, deps);
  assert.equal(calls, 2);
  responses[1].resolve({ data: { token: 'new-test', refreshToken: 'new-refresh' }, error: null });
  assert.equal(await next, true);
  assert.equal(await concurrent, true);
});

test('Axios refresh hook supplies request context so an obsolete ordinary response cannot restore token', async () => {
  const epoch = createLoginEpoch();
  const guard = createAuthResponseGuard(epoch.current);
  let responseReady = deferred<undefined>();
  let requested = deferred<undefined>();
  let token = '';
  const http = createFlatRequest(
    {
      adapter: async config => {
        requested.resolve(undefined);
        await responseReady.promise;
        return {
          config,
          status: 200,
          statusText: 'OK',
          headers: { 'new-token': 'old-test' },
          data: { code: 200, data: null }
        };
      }
    },
    {
      onRequest: config => {
        guard.capture(config);
        return config;
      },
      isBackendSuccess: () => true,
      onTokenRefresh: (value, response) => {
        if (guard.isCurrent(response?.config)) token = value;
      }
    }
  );
  const pending = http({ url: '/dedicated-test' });
  await requested.promise;
  // First verify context exists in the actual Axios callback for the current session.
  responseReady.resolve(undefined);
  await pending;
  assert.equal(token, 'old-test');
  responseReady = deferred<undefined>();
  requested = deferred<undefined>();
  token = '';
  const stale = http({ url: '/dedicated-test' });
  await requested.promise;
  epoch.advance();
  responseReady.resolve(undefined);
  await stale;
  assert.equal(token, '');
});

test('ordinary request error hook clears the current login on HTTP 401', async () => {
  const epoch = createLoginEpoch();
  const guard = createAuthResponseGuard(epoch.current);
  let resetCount = 0;
  const http = createFlatRequest(
    {
      adapter: async config => {
        const response = { config, status: 401, statusText: 'Unauthorized', headers: {}, data: {} };
        throw new AxiosError('Unauthorized', 'ERR_BAD_REQUEST', config, undefined, response);
      }
    },
    {
      onRequest: config => {
        guard.capture(config);
        return config;
      },
      onError: error => {
        guard.handleHttpAuthFailure(error.config, error.response?.status, () => {
          resetCount += 1;
          epoch.advance();
        });
      }
    }
  );

  const result = await http({ url: '/chat/conversation/new' });
  assert.equal(result.error instanceof AxiosError, true);
  assert.equal(resetCount, 1);
  assert.equal(epoch.current(), 1);
});

test('delayed ordinary HTTP 401 cannot clear a newer login', async () => {
  const epoch = createLoginEpoch();
  const guard = createAuthResponseGuard(epoch.current);
  const release = deferred<undefined>();
  const requested = deferred<undefined>();
  let resetCount = 0;
  const http = createFlatRequest(
    {
      adapter: async config => {
        requested.resolve(undefined);
        await release.promise;
        const response = { config, status: 401, statusText: 'Unauthorized', headers: {}, data: {} };
        throw new AxiosError('Unauthorized', 'ERR_BAD_REQUEST', config, undefined, response);
      }
    },
    {
      onRequest: config => {
        guard.capture(config);
        return config;
      },
      onError: error => {
        guard.handleHttpAuthFailure(error.config, error.response?.status, () => {
          resetCount += 1;
          epoch.advance();
        });
      }
    }
  );

  const stale = http({ url: '/chat/conversation/new' });
  await requested.promise;
  epoch.advance(); // Old login ended.
  epoch.advance(); // A new login began.
  release.resolve(undefined);
  const result = await stale;
  assert.equal(result.error instanceof AxiosError, true);
  assert.equal(resetCount, 0);
  assert.equal(epoch.current(), 2);
});

test('history loading blocks sending and obsolete history cannot replace a new view', async () => {
  let loading = false;
  let applied = '';
  const view = createChatViewLifecycle({
    getLoginEpoch: () => 1,
    isLoggedIn: () => true,
    onHistoryLoading: value => {
      loading = value;
    }
  });
  const history = deferred<string>();
  const pending = view.loadHistory(
    () => history.promise,
    value => {
      applied = value;
    }
  );
  pending.catch(() => {});
  assert.equal(view.canSend(), false);
  assert.equal(loading, true);
  view.invalidate();
  assert.equal(view.canSend(), true);
  history.resolve('old');
  await pending;
  assert.equal(applied, '');
});

test('explicit new conversation and send preparation share one creation in the current view', async () => {
  const view = createChatViewLifecycle({ getLoginEpoch: () => 1, isLoggedIn: () => true, onHistoryLoading: () => {} });
  let creates = 0;
  let applies = 0;
  const creation = deferred<string>();
  const load = () => {
    creates += 1;
    return creation.promise;
  };
  const apply = () => {
    applies += 1;
  };
  const explicit = view.createConversation(load, apply);
  const send = view.createConversation(load, apply);
  explicit.catch(() => {});
  send.catch(() => {});
  assert.equal(creates, 1);
  creation.resolve('conversation');
  await Promise.all([explicit, send]);
  assert.equal(applies, 1);
});

test('stopping preparation invalidates late conversation creation and preserves a new draft', async () => {
  const view = createChatViewLifecycle({ getLoginEpoch: () => 1, isLoggedIn: () => true, onHistoryLoading: () => {} });
  const gate = createSendGate();
  const creation = deferred<string>();
  let sends = 0;
  let conversation = '';
  const pending = gate.run(
    view.capture(),
    () =>
      view.createConversation(
        () => creation.promise,
        value => {
          conversation = value;
        }
      ),
    async () => {
      sends += 1;
    }
  );
  view.invalidate();
  gate.invalidate();
  creation.resolve('obsolete');
  await pending;
  assert.equal(conversation, '');
  assert.equal(sends, 0);
  assert.equal(draftAfterSubmission('submitted', 'edited while preparing'), 'edited while preparing');
  assert.equal(draftAfterSubmission('submitted', 'submitted'), '');
});
