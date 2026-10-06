/* eslint-disable no-await-in-loop -- Fixtures exercise independent requests sequentially to expose cleanup and state leaks. */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  type ChatEventEnvelope,
  ChatStreamError,
  type ChatStreamInput,
  type ChatTransportOptions,
  cancelChatRequest,
  streamChat
} from './chat-stream';

const input: ChatStreamInput = {
  requestId: '11111111-1111-4111-8111-111111111111',
  conversationId: '22222222-2222-4222-8222-222222222222',
  message: '中文😀'
};

function frame(type: string, seq: number, data: Record<string, unknown> = {}) {
  return `event: ${type}\nid: ${seq}\ndata: ${JSON.stringify({ type, ...input, message: undefined, seq, data })}\n\n`;
}
const completion = (seq = 1, status = 'finished') => frame('completion', seq, { status });

test('react rounds and tool progress reach the consumer in order', async () => {
  const { options, events } = setup(async () =>
    response(
      frame('meta', 1) +
        frame('chunk', 2, { roundId: 1, chunk: '检索说明' }) +
        frame('round_end', 3, { roundId: 1, kind: 'intermediate' }) +
        frame('tool_progress', 4, { roundId: 1, callId: 'a', tool: 'search_knowledge_base', status: 'started' }) +
        frame('tool_progress', 5, { roundId: 1, callId: 'a', tool: 'search_knowledge_base', status: 'finished' }) +
        frame('chunk', 6, { roundId: 2, chunk: '答案' }) +
        frame('round_end', 7, { roundId: 2, kind: 'final' }) +
        completion(8)
    )
  );
  assert.deepEqual(await streamChat(input, options), { status: 'finished' });
  assert.deepEqual(
    events.map(e => e.type),
    ['meta', 'chunk', 'round_end', 'tool_progress', 'tool_progress', 'chunk', 'round_end', 'completion']
  );
});
test('react rejects invalid or unfinished round protocols and cancels the request', async () => {
  const invalid = [
    frame('chunk', 1, { roundId: 1, chunk: 'draft' }) + completion(2),
    frame('chunk', 1, { roundId: 2, chunk: 'skip' }),
    frame('round_end', 1, { roundId: 1, kind: 'intermediate' }) + frame('round_end', 2, { roundId: 1, kind: 'final' }),
    frame('round_end', 1, { roundId: 1, kind: 'final' }) + frame('chunk', 2, { roundId: 2, chunk: 'late' }),
    frame('chunk', 1, { roundId: -1, chunk: 'bad' }),
    frame('round_end', 1, { roundId: 1, kind: 'unknown' }),
    frame('round_end', 1, { roundId: 1, kind: 'intermediate' }) +
      frame('tool_progress', 2, { roundId: 1, callId: 'a', tool: 'search_knowledge_base', status: 'finished' }),
    frame('chunk', 1, { chunk: 'old' }) + frame('round_end', 2, { roundId: 1, kind: 'final' })
  ];
  for (const text of invalid) {
    const { options, calls } = invalidStream(text);
    await assert.rejects(
      streamChat(input, options),
      (error: unknown) => error instanceof ChatStreamError && error.kind === 'protocol'
    );
    assert.equal(calls(), 2);
  }
});

function response(text: string, headers: HeadersInit = {}) {
  return new Response(text, { headers: { 'Content-Type': 'text/event-stream;charset=UTF-8', ...headers } });
}

function setup(fetchImpl: typeof fetch) {
  const events: ChatEventEnvelope[] = [];
  const options: ChatTransportOptions = {
    baseURL: '/proxy-default/',
    getAuthorization: () => 'Bearer dedicated-test-token',
    onNewToken: () => {},
    onEvent: event => events.push(event),
    signal: new AbortController().signal,
    fetchImpl
  };
  return { options, events };
}

const cancellationResponse = () =>
  Response.json({
    code: 200,
    message: '请求状态已确认',
    data: { requestId: input.requestId, status: 'cancelled' }
  });

function invalidStream(text: string) {
  let calls = 0;
  const configured = setup(async () => {
    calls += 1;
    return calls === 1 ? response(text) : cancellationResponse();
  });
  return { ...configured, calls: () => calls };
}

test('postUsesCurrentAuthorizationAndCorrectBaseUrl', async () => {
  const requests: Array<{ url: string; init: RequestInit }> = [];
  const { options } = setup(async (url, init) => {
    requests.push({ url: String(url), init: init! });
    return response(completion());
  });
  let authorization = 'Bearer dedicated-test-token-first';
  options.getAuthorization = () => authorization;
  await streamChat(input, options);
  authorization = 'Bearer dedicated-test-token-current';
  options.baseURL = 'https://example.invalid/api/v1';
  await streamChat(input, options);
  assert.equal(requests[0].url, '/proxy-default/chat/stream');
  assert.equal(requests[1].url, 'https://example.invalid/api/v1/chat/stream');
  const headers = new Headers(requests[1].init.headers);
  assert.equal(headers.get('Authorization'), authorization);
  assert.equal(headers.get('Content-Type'), 'application/json');
  assert.equal(headers.get('Accept'), 'text/event-stream');
  assert.equal(requests[1].init.method, 'POST');
  assert.deepEqual(JSON.parse(requests[1].init.body as string), input);
  assert.equal(requests[1].init.signal, options.signal);
});

test('uppercase UUIDs use the backend canonical identity without mutating caller input', async () => {
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
  const { options, events } = setup(async (_url, init) => {
    assert.deepEqual(JSON.parse(init!.body as string), canonical);
    return response(
      `event: completion\nid: 1\ndata: ${JSON.stringify({ type: 'completion', requestId: canonical.requestId, conversationId: canonical.conversationId, seq: 1, data: { status: 'finished' } })}\n\n`
    );
  });
  assert.deepEqual(await streamChat(uppercase, options), { status: 'finished' });
  assert.equal(events[0].conversationId, canonical.conversationId);
  assert.equal(events[0].requestId, canonical.requestId);
  assert.equal(uppercase.conversationId, canonical.conversationId.toUpperCase());
  assert.equal(uppercase.requestId, canonical.requestId.toUpperCase());
  options.fetchImpl = async url => {
    assert.equal(String(url), `/proxy-default/chat/requests/${canonical.requestId}/cancel`);
    return Response.json({ code: 200, data: { requestId: canonical.requestId, status: 'cancelled' } });
  };
  assert.deepEqual(await cancelChatRequest(uppercase.requestId, options), {
    requestId: canonical.requestId,
    status: 'cancelled'
  });
});

test('UUID normalization leaves malformed input unchanged for strict backend validation', async () => {
  const malformed = { ...input, conversationId: 'NOT-A-UUID', requestId: 'BAD-REQUEST-ID' };
  const { options } = setup(async (_url, init) => {
    assert.deepEqual(JSON.parse(init!.body as string), malformed);
    return Response.json({ code: 400, message: '参数无效', data: { errorCode: 'INVALID_REQUEST' } }, { status: 400 });
  });
  await assert.rejects(streamChat(malformed, options), (error: any) => error.kind === 'http' && error.status === 400);
});

test('newTokenHandledBeforeBody', async () => {
  let tokenUpdated = false;
  const body = new ReadableStream<Uint8Array>(
    {
      pull(controller) {
        assert.equal(tokenUpdated, true);
        controller.enqueue(new TextEncoder().encode(completion()));
        controller.close();
      }
    },
    { highWaterMark: 0 }
  );
  const { options } = setup(
    async () =>
      new Response(body, {
        headers: { 'Content-Type': 'text/event-stream', 'New-Token': 'dedicated-test-token-new' }
      })
  );
  options.onNewToken = token => {
    assert.equal(token, 'dedicated-test-token-new');
    tokenUpdated = true;
  };
  await streamChat(input, options);
});

test('httpJsonErrorDoesNotEnterParser', async () => {
  const { options, events } = setup(async () =>
    Response.json(
      {
        code: 409,
        message: '当前会话正在生成回答',
        data: { errorCode: 'CONVERSATION_BUSY' }
      },
      { status: 409 }
    )
  );
  await assert.rejects(streamChat(input, options), error => {
    assert.ok(error instanceof ChatStreamError);
    assert.equal(error.kind, 'http');
    assert.equal(error.status, 409);
    assert.equal(error.errorCode, 'CONVERSATION_BUSY');
    assert.equal(error.message, '当前会话正在生成回答');
    return true;
  });
  assert.deepEqual(events, []);
});

test('wrongContentTypeIsRejected', async () => {
  const configured = invalidStream('');
  configured.options.fetchImpl = async () => Response.json({});
  await assert.rejects(
    streamChat(input, configured.options),
    error => error instanceof ChatStreamError && error.kind === 'protocol'
  );
});

test('missingBodyIsRejected', async () => {
  const { options } = setup(async () => new Response(null, { headers: { 'Content-Type': 'text/event-stream' } }));
  await assert.rejects(streamChat(input, options), /body/i);
});

test('missingCompletionIsInterrupted', async () => {
  const configured = invalidStream(frame('chunk', 1, { chunk: 'partial' }));
  await assert.rejects(
    streamChat(input, configured.options),
    error => error instanceof ChatStreamError && error.kind === 'interrupted'
  );
  assert.equal(configured.events[0].data.chunk, 'partial');
  assert.equal(configured.calls(), 2);
});

test('knownPayloadAndSequenceAreValidated', async () => {
  const invalid = [
    frame('chunk', 1, { chunk: 3 }),
    frame('meta', 1, { unexpected: true }),
    frame('tool_progress', 1, { tool: 'private_tool', status: 'started' }),
    frame('tool_progress', 1, { tool: 'search_knowledge_base', status: 'other' }),
    frame('error', 1, { code: 'PRIVATE_ERROR', message: 'error' }),
    frame('error', 1, { code: 'MODEL_ERROR', message: 3 }),
    frame('completion', 1, { status: 'running' }),
    frame('chunk', 2, { chunk: 'gap' }),
    frame('chunk', 1, { chunk: 'text' }).replace('"seq":1', '"seq":1.5'),
    frame('chunk', 1, { chunk: 'text' }).replace(input.requestId, 'other'),
    frame('chunk', 1, { chunk: 'text' }).replace(input.conversationId, 'other'),
    frame('chunk', 1, { chunk: 'text' }).replace('"type":"chunk"', '"type":"meta"'),
    frame('meta', 1).replace('"data":{}', '"data":[]'),
    frame('meta', 1).replace('"seq":1', '"seq":"1"'),
    'event: chunk\nid: 1\ndata: invalid json\n\n',
    frame('meta', 1).replace('id: 1', 'id: 2'),
    frame('meta', 1).replace('id: 1', 'id: 01')
  ];
  for (const fixture of invalid) {
    const configured = invalidStream(fixture);
    await assert.rejects(
      streamChat(input, configured.options),
      error => error instanceof ChatStreamError && error.kind === 'protocol'
    );
    assert.deepEqual(configured.events, []);
    assert.equal(configured.calls(), 2);
  }
});

test('duplicateSequenceDoesNotAppendAgain', async () => {
  const { options, events } = setup(async () =>
    response(frame('chunk', 1, { chunk: 'one' }) + frame('chunk', 1, { chunk: 'one' }) + completion(2))
  );
  assert.deepEqual(await streamChat(input, options), { status: 'finished' });
  assert.equal(events.filter(event => event.type === 'chunk').length, 1);
});

test('unknownTypeAdvancesCursorWithoutUiEffect', async () => {
  const { options, events } = setup(async () =>
    response(`: ping\n\n${frame('future_event', 1, { version: 2 })}${completion(2)}`)
  );
  assert.deepEqual(await streamChat(input, options), { status: 'finished' });
  assert.deepEqual(
    events.map(event => event.type),
    ['completion']
  );
});

test('errorMustBeFollowedByFailureCompletion', async () => {
  for (const fixture of [
    frame('error', 1, { code: 'MODEL_ERROR', message: 'failed' }) + frame('chunk', 2, { chunk: 'too late' }),
    frame('error', 1, { code: 'MODEL_ERROR', message: 'failed' }) + completion(2, 'finished'),
    frame('error', 1, { code: 'STREAM_TIMEOUT', message: 'timeout' }) + completion(2, 'failed')
  ]) {
    const configured = invalidStream(fixture);
    await assert.rejects(streamChat(input, configured.options), /protocol|completion/i);
    assert.equal(configured.events.length, 1);
  }
});

test('allTerminalStatusesAreReturned', async () => {
  for (const status of ['finished', 'cancelled', 'failed', 'timed_out']) {
    const { options } = setup(async () => response(completion(1, status)));
    assert.deepEqual(await streamChat(input, options), { status });
  }
});

test('abortDoesNotReplayPost', async () => {
  let calls = 0;
  const controller = new AbortController();
  const { options } = setup(async () => {
    calls += 1;
    throw new DOMException('Aborted', 'AbortError');
  });
  options.signal = controller.signal;
  controller.abort();
  await assert.rejects(streamChat(input, options), error => error instanceof Error && error.name === 'AbortError');
  assert.equal(calls, 0);
});

test('abortDuringReadCancelsAndReleasesReader', async () => {
  let streamCancelled = false;
  let calls = 0;
  const controller = new AbortController();
  const body = new ReadableStream<Uint8Array>({
    cancel() {
      streamCancelled = true;
    }
  });
  const { options } = setup(async () => {
    calls += 1;
    return new Response(body, { headers: { 'Content-Type': 'text/event-stream' } });
  });
  options.signal = controller.signal;
  const pending = streamChat(input, options);
  const rejected = assert.rejects(pending, error => error instanceof Error && error.name === 'AbortError');
  await new Promise(resolve => {
    setTimeout(resolve, 0);
  });
  controller.abort();
  await rejected;
  assert.equal(calls, 1);
  assert.equal(streamCancelled, true);
  assert.equal(body.locked, false);
});

test('protocolFailureCancelsAndReleasesReader', async () => {
  let cancelled = false;
  const controller = new AbortController();
  const requests: RequestInit[] = [];
  const body = new ReadableStream<Uint8Array>({
    start(stream) {
      stream.enqueue(new TextEncoder().encode(frame('chunk', 2, { chunk: 'gap' })));
    },
    cancel() {
      cancelled = true;
    }
  });
  const { options } = setup(async (_url, init) => {
    requests.push(init!);
    return requests.length === 1
      ? new Response(body, { headers: { 'Content-Type': 'text/event-stream' } })
      : cancellationResponse();
  });
  options.signal = controller.signal;
  await assert.rejects(streamChat(input, options), /sequence/i);
  assert.equal(cancelled, true);
  assert.equal(body.locked, false);
  assert.equal(requests.length, 2);
  assert.notEqual(requests[1].signal, options.signal);
  assert.equal(requests[1].signal?.aborted, false);
});

test('networkReadFailureIsInterruptedAndCancelsServerWithoutReplay', async () => {
  let reads = 0;
  let calls = 0;
  const body = new ReadableStream<Uint8Array>(
    {
      pull(controller) {
        reads += 1;
        if (reads === 1) controller.enqueue(new TextEncoder().encode(frame('chunk', 1, { chunk: 'partial' })));
        else controller.error(new TypeError('Network read failed'));
      }
    },
    { highWaterMark: 0 }
  );
  const { options, events } = setup(async () => {
    calls += 1;
    return calls === 1
      ? new Response(body, { headers: { 'Content-Type': 'text/event-stream' } })
      : cancellationResponse();
  });
  await assert.rejects(
    streamChat(input, options),
    error => error instanceof ChatStreamError && error.kind === 'interrupted'
  );
  assert.equal(events[0].data.chunk, 'partial');
  assert.equal(calls, 2);
  assert.equal(body.locked, false);
});

test('networkFailureBeforeHeadersIsInterruptedAndCancelsWithoutReplay', async () => {
  const urls: string[] = [];
  const { options } = setup(async url => {
    urls.push(String(url));
    if (urls.length === 1) throw new TypeError('Connection lost before response headers');
    return cancellationResponse();
  });
  await assert.rejects(
    streamChat(input, options),
    error => error instanceof ChatStreamError && error.kind === 'interrupted'
  );
  assert.deepEqual(urls, ['/proxy-default/chat/stream', `/proxy-default/chat/requests/${input.requestId}/cancel`]);
});

test('oversizedCommentStreamIsCancelled', async () => {
  const configured = invalidStream(`: ${'x'.repeat(262144)}\n\n`);
  await assert.rejects(streamChat(input, configured.options), /limit/i);
  assert.equal(configured.calls(), 2);
});

test('httpFailureDoesNotReplayOrCancel', async () => {
  let calls = 0;
  const { options } = setup(async () => {
    calls += 1;
    return Response.json({ message: 'unauthenticated' }, { status: 401 });
  });
  await assert.rejects(streamChat(input, options), error => error instanceof ChatStreamError && error.status === 401);
  assert.equal(calls, 1);
});

test('cancelUsesIndependentSignal', async () => {
  const generation = new AbortController();
  generation.abort();
  const cancellation = new AbortController();
  const { options } = setup(async (url, init) => {
    assert.equal(String(url), `/proxy-default/chat/requests/${input.requestId}/cancel`);
    assert.equal(init?.method, 'POST');
    assert.equal(new Headers(init?.headers).get('Authorization'), 'Bearer dedicated-test-token');
    assert.equal(init?.signal, cancellation.signal);
    assert.equal(init?.signal?.aborted, false);
    return cancellationResponse();
  });
  assert.deepEqual(await cancelChatRequest(input.requestId, { ...options, signal: cancellation.signal }), {
    requestId: input.requestId,
    status: 'cancelled'
  });
});

test('cancelReturnsActualStatesAndValidatesResponse', async () => {
  for (const status of ['completing', 'finished', 'cancelled', 'failed', 'timed_out']) {
    const { options } = setup(async () => Response.json({ code: 200, data: { requestId: input.requestId, status } }));
    assert.deepEqual(await cancelChatRequest(input.requestId, options), { requestId: input.requestId, status });
  }
  for (const data of [
    { requestId: 'other', status: 'cancelled' },
    { requestId: input.requestId, status: 'running' },
    {}
  ]) {
    const { options } = setup(async () => Response.json({ code: 200, data }));
    await assert.rejects(cancelChatRequest(input.requestId, options), /response/i);
  }
});
