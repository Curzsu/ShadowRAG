import assert from 'node:assert/strict';
import test from 'node:test';
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
import { readFileSync } from 'node:fs';
import { createContext, runInContext } from 'node:vm';

const input = {
  conversationId: 'b55a0cb3-4c51-4b14-9727-761a3101fdd7',
  requestId: 'c49d2181-6299-44ee-bd73-28b6116d2879',
  message: '问题'
};
const helper = await import(pathToFileURL(resolve('../src/main/resources/static/chat-stream.mjs')).href).catch(
  () => ({})
);
const bytes = new TextEncoder();
// eslint-disable-next-line max-params -- Literal SSE fixture supports each network line ending.
function frame(type: string, seq: number, data: object, newline = '\n') {
  const body = JSON.stringify({ type, ...input, seq, data });
  return [`event: ${type}`, `id: ${seq}`, `data: ${body}`, '', ''].join(newline);
}
function response(text: string, bytewise = false, controls: { onCancel?: () => void; holdOpen?: boolean } = {}) {
  const { onCancel = () => {}, holdOpen = false } = controls;
  const encoded = bytes.encode(text);
  let offset = 0;
  return new Response(
    new ReadableStream({
      pull(controller) {
        if (offset === encoded.length) {
          if (!holdOpen) controller.close();
          return;
        }
        const end = bytewise ? offset + 1 : encoded.length;
        controller.enqueue(encoded.slice(offset, end));
        offset = end;
      },
      cancel: onCancel
    }),
    { headers: { 'Content-Type': 'text/event-stream', 'New-Token': 'replacement' } }
  );
}
const options = { baseURL: '/api/v1', getAuthorization: () => 'Bearer test-only' };

test('static helper canonicalizes uppercase UUIDs before POST, receive and cancel', async () => {
  const uppercase = {
    ...input,
    conversationId: input.conversationId.toUpperCase(),
    requestId: input.requestId.toUpperCase()
  };
  const events: any[] = [];
  const result = await helper.streamChat(uppercase, {
    ...options,
    onEvent: (event: any) => events.push(event),
    fetchImpl: async (_url: string, init: any) => {
      assert.deepEqual(JSON.parse(init.body), input);
      return response(frame('meta', 1, {}) + frame('completion', 2, { status: 'finished' }));
    }
  });
  assert.equal(result.status, 'finished');
  assert.equal(events[0].conversationId, input.conversationId);
  assert.equal(uppercase.conversationId, input.conversationId.toUpperCase());
  const cancel = await helper.cancelChatRequest(uppercase.requestId, {
    ...options,
    fetchImpl: async (url: string) => {
      assert.equal(url, `/api/v1/chat/requests/${input.requestId}/cancel`);
      return Response.json({ code: 200, data: { requestId: input.requestId, status: 'cancelled' } });
    }
  });
  assert.equal(cancel.requestId, input.requestId);
});

function pageHarness(page: string, fetchImpl: (...args: any[]) => Promise<Response>) {
  const elements = new Map<string, any>();
  const values = new Map<string, string>([['token', 'login-A']]);
  const element = () => ({
    value: '',
    textContent: '',
    innerHTML: '',
    children: [] as any[],
    style: {},
    classList: { add() {}, remove() {} },
    appendChild(child: any) {
      this.children.push(child);
    },
    replaceChildren(...children: any[]) {
      this.children = children;
    }
  });
  const context = createContext({
    document: {
      getElementById(id: string) {
        if (!elements.has(id)) elements.set(id, element());
        return elements.get(id);
      },
      createElement: element,
      querySelectorAll: () => [],
      addEventListener() {}
    },
    window: {
      addEventListener() {},
      ChatStreamTransport: {
        cancelChatRequest: async () => ({ status: 'cancelled' }),
        streamChat: () => new Promise(() => {})
      }
    },
    localStorage: {
      getItem: (key: string) => values.get(key),
      setItem: (key: string, value: string) => values.set(key, value),
      removeItem: (key: string) => values.delete(key)
    },
    fetch: fetchImpl,
    AbortController,
    crypto: { randomUUID: () => input.requestId },
    console
  });
  const html = readFileSync(resolve(`../src/main/resources/${page}`), 'utf8');
  const script = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/g)].find(
    match => !/type="module"|src=/.test(match[1])
  );
  assert.ok(script);
  runInContext(script[2], context);
  return {
    context,
    values,
    get: (id: string) => context.document.getElementById(id),
    run: (code: string) => runInContext(code, context)
  };
}

for (const page of ['test.html', 'static/test.html']) {
  test(`${page}: final display excludes intermediate round text`, async () => {
    const harness = pageHarness(page, async () => new Response());
    harness.get('chat-conversation-id').value = input.conversationId;
    harness.get('message-input').value = '问题';
    harness.context.window.ChatStreamTransport.streamChat = async (_input: unknown, transportOptions: any) => {
      transportOptions.onEvent({ type: 'chunk', data: { roundId: 1, chunk: '检索说明' } });
      transportOptions.onEvent({ type: 'round_end', data: { roundId: 1, kind: 'intermediate' } });
      transportOptions.onEvent({ type: 'chunk', data: { roundId: 2, chunk: '最终答案' } });
      transportOptions.onEvent({ type: 'round_end', data: { roundId: 2, kind: 'final' } });
      return { status: 'finished' };
    };
    await harness.run('sendMessage()');
    assert.equal(harness.get('chat-messages').children[1].textContent, '最终答案');
  });
  test(`${page}: explicit credential refresh cannot replace the stored login`, () => {
    const harness = pageHarness(page, async () => new Response());
    harness.get('chat-authorization').value = 'Bearer explicit-B';
    harness.run("chatOptions().onNewToken('refreshed-B')");
    assert.equal(harness.get('chat-authorization').value, 'Bearer refreshed-B');
    assert.equal(harness.values.get('token'), 'login-A');
    assert.equal(harness.run('token'), 'login-A');
    harness.get('chat-authorization').value = '';
    harness.run("chatOptions().onNewToken('refreshed-A')");
    assert.equal(harness.values.get('token'), 'refreshed-A');
  });

  for (const change of ['logout', 'newer create', 'selection', 'send'])
    test(`${page}: pending creation cannot overwrite ${change}`, async () => {
      const resolvers: ((response: Response) => void)[] = [];
      const harness = pageHarness(
        page,
        () =>
          new Promise(resolveResponse => {
            resolvers.push(resolveResponse);
          })
      );
      harness.get('chat-conversation-id').value = 'original';
      const first = harness.run('createChatConversation()');
      if (change === 'logout') harness.run("logout(); token = 'login-B'; authEpoch += 1");
      if (change === 'newer create') {
        const newer = harness.run('createChatConversation()');
        resolvers[1](new Response(JSON.stringify({ code: 200, data: { conversationId: 'newer' } })));
        await newer;
      }
      if (change === 'selection') {
        harness.get('chat-conversation-id').value = 'selected';
        harness.run('abandonChat()');
      }
      if (change === 'send') {
        harness.get('message-input').value = 'new question';
        harness.run('sendMessage()');
      }
      const expectedId = harness.get('chat-conversation-id').value;
      const expectedStatus = harness.get('chat-status').textContent;
      resolvers[0](new Response(JSON.stringify({ code: 200, data: { conversationId: 'stale' } })));
      await first;
      assert.equal(harness.get('chat-conversation-id').value, expectedId);
      assert.equal(harness.get('chat-status').textContent, expectedStatus);
    });

  test(`${page}: history uses owned list and switch routes with safe text and explicit selection`, async () => {
    const urls: string[] = [];
    const harness = pageHarness(page, async (url: string, init: any) => {
      urls.push(url);
      assert.equal(init.headers.Authorization, 'Bearer login-A');
      if (urls.length === 1) {
        assert.equal(url, '/api/v1/chat/conversation/list');
        return new Response(
          JSON.stringify({
            code: 200,
            data: [{ conversationId: input.conversationId, title: '<img src=x onerror=bad()>' }]
          })
        );
      }
      assert.equal(url, `/api/v1/chat/conversation/${input.conversationId}/switch`);
      assert.equal(init.method, 'POST');
      return new Response(
        JSON.stringify({
          code: 200,
          data: {
            conversationId: input.conversationId,
            messages: [{ role: 'assistant', content: '<b>saved answer</b>' }]
          }
        })
      );
    });
    await harness.run('getConversations()');
    assert.deepEqual(urls, ['/api/v1/chat/conversation/list']);
    assert.equal(harness.get('history-response').children.length, 1);
    assert.equal(harness.get('history-response').children[0].textContent, '<img src=x onerror=bad()>');
    await harness.get('history-response').children[0].onclick();
    assert.equal(harness.get('chat-conversation-id').value, input.conversationId);
    assert.equal(harness.get('chat-messages').children[0].textContent, '<b>saved answer</b>');
    assert.equal(harness.get('chat-messages').children[0].innerHTML, '');
  });
}
function available() {
  assert.equal(typeof helper.streamChat, 'function', 'Static SSE helper must expose streamChat');
}

test('static transport preserves react round events and rejects missing final confirmation', async () => {
  available();
  const events: any[] = [];
  const text =
    frame('chunk', 1, { roundId: 1, chunk: '说明' }) +
    frame('round_end', 2, { roundId: 1, kind: 'intermediate' }) +
    frame('chunk', 3, { roundId: 2, chunk: '答案' }) +
    frame('round_end', 4, { roundId: 2, kind: 'final' }) +
    frame('completion', 5, { status: 'finished' });
  await helper.streamChat(input, {
    ...options,
    onEvent: (event: any) => events.push(event),
    fetchImpl: async () => response(text)
  });
  assert.equal(events.filter(event => event.type === 'round_end').length, 2);
  let count = 0;
  await assert.rejects(
    helper.streamChat(input, {
      ...options,
      onEvent() {},
      fetchImpl: async () => {
        count += 1;
        return count === 1
          ? response(frame('chunk', 1, { roundId: 1, chunk: 'draft' }) + frame('completion', 2, { status: 'finished' }))
          : Response.json({ code: 200, data: { requestId: input.requestId, status: 'cancelled' } });
      }
    }),
    (error: any) => error.kind === 'protocol'
  );
});

test('static helper dispatches Chinese and emoji across bytes and all SSE line endings', async () => {
  available();
  const events: any[] = [];
  const text = `: heartbeat\r\r${frame('meta', 1, {}, '\r\n')}${frame(
    'chunk',
    2,
    { chunk: '中文😀\n下一行' },
    '\r'
  )}${frame('completion', 3, { status: 'finished' })}`;
  const result = await helper.streamChat(input, {
    ...options,
    onEvent: (event: any) => events.push(event),
    fetchImpl: async () => response(text, true)
  });
  assert.equal(result.status, 'finished');
  assert.deepEqual(
    events.map(event => [event.type, event.seq]),
    [
      ['meta', 1],
      ['chunk', 2],
      ['completion', 3]
    ]
  );
  assert.equal(events[1].data.chunk, '中文😀\n下一行');
});

test('static helper parses multiple events and multiline data in one read, ignoring duplicate sequence', async () => {
  available();
  const events: any[] = [];
  const chunk = frame('chunk', 2, { chunk: 'answer' }).replace('data: {', 'data: {\ndata: ');
  const result = await helper.streamChat(input, {
    ...options,
    onEvent: (event: any) => events.push(event),
    fetchImpl: async () =>
      response(frame('meta', 1, {}) + chunk + chunk + frame('completion', 3, { status: 'cancelled' }))
  });
  assert.equal(result.status, 'cancelled');
  assert.equal(events.length, 3);
  assert.equal(events[1].data.chunk, 'answer');
});

test('static helper discards unfinished EOF event and cancels with an independent signal', async () => {
  available();
  const controller = new AbortController();
  const calls: any[] = [];
  const events: any[] = [];
  await assert.rejects(
    helper.streamChat(input, {
      ...options,
      signal: controller.signal,
      onEvent: (event: any) => events.push(event),
      fetchImpl: async (url: string, init: any) => {
        calls.push({ url, init });
        return calls.length === 1
          ? response(frame('meta', 1, {}) + frame('chunk', 2, { chunk: 'partial' }).slice(0, -1))
          : new Response(JSON.stringify({ code: 200, data: { requestId: input.requestId, status: 'cancelled' } }));
      }
    }),
    (error: any) => error.kind === 'interrupted'
  );
  assert.deepEqual(
    events.map(event => event.type),
    ['meta']
  );
  assert.equal(calls.length, 2);
  assert.equal(calls[1].url, `/api/v1/chat/requests/${input.requestId}/cancel`);
  assert.notEqual(calls[1].init.signal, controller.signal);
  assert.equal(calls[1].init.headers.get('Authorization'), 'Bearer test-only');
});

test('static helper abort unblocks pending read and releases reader', async () => {
  available();
  const controller = new AbortController();
  let cancelled = false;
  const body = new ReadableStream({
    start(stream) {
      stream.enqueue(bytes.encode(frame('meta', 1, {})));
    },
    cancel() {
      cancelled = true;
    }
  });
  const promise = helper.streamChat(input, {
    ...options,
    signal: controller.signal,
    onEvent: () => controller.abort(),
    fetchImpl: async () => new Response(body, { headers: { 'Content-Type': 'text/event-stream' } })
  });
  await assert.rejects(promise, (error: any) => error.name === 'AbortError');
  assert.equal(cancelled, true);
  assert.equal(body.locked, false);
});

test('static cancellation preserves completing state and sends the actual HTTP contract', async () => {
  available();
  let refreshed = '';
  const result = await helper.cancelChatRequest(input.requestId, {
    ...options,
    onNewToken: (value: string) => {
      refreshed = value;
    },
    fetchImpl: async (url: string, init: any) => {
      assert.equal(url, `/api/v1/chat/requests/${input.requestId}/cancel`);
      assert.equal(init.method, 'POST');
      assert.equal(init.headers.get('Authorization'), 'Bearer test-only');
      return new Response(JSON.stringify({ code: 200, data: { requestId: input.requestId, status: 'completing' } }), {
        headers: { 'New-Token': 'new-token' }
      });
    }
  });
  assert.equal(result.status, 'completing');
  assert.equal(refreshed, 'new-token');
});

for (const [name, text] of [
  ['invalid JSON', 'event: meta\nid: 1\ndata: {\n\n'],
  ['sequence gap', frame('meta', 1, {}) + frame('chunk', 3, { chunk: 'bad' })],
  ['oversized unfinished event', `data: ${'x'.repeat(262145)}`]
])
  test(`static helper rejects ${name} and cancels the stream`, async () => {
    available();
    let requests = 0;
    let cancelled = false;
    await assert.rejects(
      helper.streamChat(input, {
        ...options,
        onEvent() {},
        fetchImpl: async () => {
          requests += 1;
          return requests === 1
            ? response(text, false, {
                onCancel: () => {
                  cancelled = true;
                },
                holdOpen: true
              })
            : new Response(JSON.stringify({ code: 200, data: { requestId: input.requestId, status: 'cancelled' } }));
        }
      }),
      (error: any) => error.kind === 'protocol'
    );
    assert.equal(requests, 2);
    assert.equal(cancelled, true);
  });

test('static helper reads HTTP errors without replaying generation or parsing SSE', async () => {
  available();
  let calls = 0;
  await assert.rejects(
    helper.streamChat(input, {
      ...options,
      onEvent() {
        assert.fail('HTTP error dispatched as SSE');
      },
      fetchImpl: async () => {
        calls += 1;
        return new Response(
          JSON.stringify({ code: 401, message: '请先登录', data: { errorCode: 'UNAUTHENTICATED' } }),
          { status: 401 }
        );
      }
    }),
    (error: any) => error.kind === 'http' && error.status === 401 && error.errorCode === 'UNAUTHENTICATED'
  );
  assert.equal(calls, 1);
});
