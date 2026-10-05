// Standalone browser transport mirrors frontend/utils/sse and service/api/chat-stream.
// Keep the HTTP/envelope contract in sync; static-chat-stream.test.ts exercises this artifact.
export function createSseParser(onFrame, maxEventChars = 262144) {
    if (!Number.isSafeInteger(maxEventChars) || maxEventChars < 1)
        throw new Error('Invalid SSE event buffer limit');
    const decoder = new TextDecoder('utf-8');
    let line = '';
    let event = '';
    let id = '';
    let data = [];
    let eventChars = 0;
    let skipLf = false;
    let finished = false;
    function countChar() {
        eventChars += 1;
        if (eventChars > maxEventChars)
            throw new Error('SSE event buffer limit exceeded');
    }
    function endLine() {
        if (line === '') {
            const frame = data.length ? { event: event || 'message', id, data: data.join('\n') } : undefined;
            event = '';
            id = '';
            data = [];
            eventChars = 0;
            if (frame)
                onFrame(frame);
            return;
        }
        countChar();
        const separator = line.indexOf(':');
        const field = separator === -1 ? line : line.slice(0, separator);
        let value = separator === -1 ? '' : line.slice(separator + 1);
        if (value.startsWith(' '))
            value = value.slice(1);
        if (field === 'event')
            event = value;
        if (field === 'id' && !value.includes('\0'))
            id = value;
        if (field === 'data')
            data.push(value);
        line = '';
    }
    return {
        feed(bytes) {
            if (finished)
                throw new Error('SSE parser is finished');
            const text = decoder.decode(bytes, { stream: true });
            for (let index = 0; index < text.length; index += 1) {
                const char = text[index];
                const ignoredLf = skipLf && char === '\n';
                if (skipLf) {
                    skipLf = false;
                    if (char === '\n') {
                        if (eventChars)
                            countChar();
                    }
                }
                if (!ignoredLf && (char === '\r' || char === '\n')) {
                    endLine();
                    skipLf = char === '\r';
                }
                else if (!ignoredLf) {
                    countChar();
                    line += char;
                }
            }
        },
        finish() {
            if (finished)
                return;
            finished = true;
            decoder.decode();
            line = '';
            event = '';
            id = '';
            data = [];
            eventChars = 0;
            skipLf = false;
        }
    };
}
function canonicalUuid(value) {
    return typeof value === 'string' && /^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/i.test(value)
        ? value.toLowerCase()
        : value;
}
/** Match backend UUID canonicalization without changing invalid input or the caller's object. */
export function canonicalizeChatInput(input) {
    return {
        ...input,
        conversationId: canonicalUuid(input.conversationId),
        requestId: canonicalUuid(input.requestId)
    };
}
export class ChatStreamError extends Error {
    kind;
    status;
    errorCode;
    constructor(kind, message, details) {
        super(message);
        this.kind = kind;
        this.name = 'ChatStreamError';
        this.status = details?.status;
        this.errorCode = details?.errorCode;
    }
}
const terminalStatuses = new Set(['finished', 'cancelled', 'failed', 'timed_out']);
const errorCodes = new Set([
    'MODEL_ERROR',
    'TOOL_ERROR',
    'HISTORY_ERROR',
    'PERSISTENCE_ERROR',
    'STREAM_TIMEOUT',
    'STREAM_OVERFLOW',
    'INTERNAL_ERROR'
]);
const knownTypes = new Set(['meta', 'chunk', 'tool_progress', 'error', 'completion']);
function record(value) {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}
function protocol(message) {
    throw new ChatStreamError('protocol', message);
}
function url(baseURL, path) {
    return `${baseURL.replace(/\/+$/, '')}/${path}`;
}
function headers(options, streaming) {
    const result = new Headers();
    const authorization = options.getAuthorization();
    if (authorization)
        result.set('Authorization', authorization);
    result.set('Accept', streaming ? 'text/event-stream' : 'application/json');
    if (streaming)
        result.set('Content-Type', 'application/json');
    return result;
}
async function checkResponse(response, options) {
    const token = response.headers.get('New-Token');
    // The caller must verify that this response still belongs to its current login session.
    if (token)
        options.onNewToken?.(token);
    if (response.ok)
        return;
    let body;
    try {
        body = await response.json();
    }
    catch {
        /* A non-JSON proxy error is still an HTTP failure. */
    }
    const message = record(body) && typeof body.message === 'string' ? body.message : `Chat request failed (HTTP ${response.status})`;
    const errorCode = record(body) && record(body.data) && typeof body.data.errorCode === 'string' ? body.data.errorCode : undefined;
    throw new ChatStreamError('http', message, { status: response.status, errorCode });
}
function validateFrame(frame, input) {
    let envelope;
    try {
        envelope = JSON.parse(frame.data);
    }
    catch {
        protocol('Invalid chat event JSON');
    }
    if (!record(envelope) ||
        typeof envelope.type !== 'string' ||
        !envelope.type ||
        envelope.type !== frame.event ||
        envelope.requestId !== input.requestId ||
        envelope.conversationId !== input.conversationId ||
        !Number.isSafeInteger(envelope.seq) ||
        envelope.seq < 1 ||
        frame.id !== String(envelope.seq) ||
        !record(envelope.data)) {
        protocol('Invalid chat event envelope');
    }
    validatePayload(envelope.type, envelope.data);
    return envelope;
}
function validatePayload(type, data) {
    if (type === 'meta' && Object.keys(data).length !== 0)
        protocol('Invalid meta payload');
    if (type === 'chunk' && typeof data.chunk !== 'string')
        protocol('Invalid chunk payload');
    if (type === 'tool_progress' &&
        (data.tool !== 'search_knowledge_base' || !['started', 'finished'].includes(data.status))) {
        protocol('Invalid tool progress payload');
    }
    if (type === 'error' &&
        (typeof data.code !== 'string' || !errorCodes.has(data.code) || typeof data.message !== 'string')) {
        protocol('Invalid error payload');
    }
    if (type === 'completion' && (typeof data.status !== 'string' || !terminalStatuses.has(data.status))) {
        protocol('Invalid completion payload');
    }
}
function abortReason(signal) {
    return signal.reason ?? new DOMException('Aborted', 'AbortError');
}
function isAbort(error) {
    return error instanceof Error && error.name === 'AbortError';
}
async function startStream(input, options) {
    try {
        return await (options.fetchImpl ?? fetch)(url(options.baseURL, 'chat/stream'), {
            method: 'POST',
            headers: headers(options, true),
            body: JSON.stringify(input),
            signal: options.signal
        });
    }
    catch (error) {
        if (isAbort(error))
            throw error;
        throw new ChatStreamError('interrupted', 'Chat stream connection interrupted');
    }
}
function createReceiver(input, onEvent) {
    let terminal;
    let lastSeq = 0;
    let failureCode;
    return {
        getStatus: () => terminal,
        onFrame(frame) {
            const event = validateFrame(frame, input);
            if (event.seq <= lastSeq)
                return;
            if (terminal)
                protocol('Chat event after completion');
            if (event.seq !== lastSeq + 1)
                protocol('Chat event sequence gap');
            if (failureCode && event.type !== 'completion')
                protocol('Expected completion after error');
            if (event.type === 'completion' &&
                failureCode &&
                event.data.status !== (failureCode === 'STREAM_TIMEOUT' ? 'timed_out' : 'failed')) {
                protocol('Invalid completion after error');
            }
            lastSeq = event.seq;
            if (event.type === 'error')
                failureCode = event.data.code;
            if (event.type === 'completion')
                terminal = event.data.status;
            if (knownTypes.has(event.type))
                onEvent(event);
        }
    };
}
async function releaseStream(reader, response, ended) {
    if (reader) {
        try {
            if (!ended)
                await reader.cancel();
        }
        catch {
            /* Preserve the original transport outcome. */
        }
        reader.releaseLock();
    }
    else if (response?.body && !response.body.locked) {
        try {
            await response.body.cancel();
        }
        catch {
            /* Preserve the original HTTP/protocol failure. */
        }
    }
}
async function readChunk(reader, signal) {
    signal?.throwIfAborted();
    try {
        const result = await reader.read();
        signal?.throwIfAborted();
        return result;
    }
    catch (error) {
        if (signal?.aborted || isAbort(error))
            throw error;
        throw new ChatStreamError('interrupted', 'Chat stream connection interrupted');
    }
}
function feedParser(parser, bytes) {
    try {
        parser.feed(bytes);
    }
    catch (error) {
        if (error instanceof ChatStreamError)
            throw error;
        throw new ChatStreamError('protocol', error instanceof Error && /buffer limit/.test(error.message) ? error.message : 'Chat stream processing failed');
    }
}
/** One generation POST; authentication failures and aborts never replay it. */
export async function streamChat(command, options) {
    const input = canonicalizeChatInput(command);
    options.signal?.throwIfAborted();
    let response;
    let reader;
    let parser;
    let abortListener;
    let ended = false;
    const receiver = createReceiver(input, options.onEvent);
    try {
        response = await startStream(input, options);
        await checkResponse(response, options);
        if (response.headers.get('Content-Type')?.split(';', 1)[0].trim().toLowerCase() !== 'text/event-stream') {
            protocol('Expected text/event-stream response');
        }
        if (!response.body)
            protocol('Missing chat stream body');
        reader = response.body.getReader();
        abortListener = () => {
            reader?.cancel().catch(() => { });
        };
        options.signal?.addEventListener('abort', abortListener, { once: true });
        options.signal?.throwIfAborted();
        parser = createSseParser(receiver.onFrame);
        while (true) {
            // eslint-disable-next-line no-await-in-loop -- Network reads and sequence validation must remain serial.
            const read = await readChunk(reader, options.signal);
            if (read.done) {
                ended = true;
                break;
            }
            feedParser(parser, read.value);
            const terminal = receiver.getStatus();
            if (terminal)
                return { status: terminal };
        }
        throw new ChatStreamError('interrupted', 'Chat stream ended without completion');
    }
    catch (error) {
        if (options.signal?.aborted)
            throw abortReason(options.signal);
        if (error instanceof ChatStreamError && (error.kind === 'protocol' || error.kind === 'interrupted')) {
            // Separate cancellation from the generation signal; never wait for it to unblock local cleanup.
            cancelChatRequest(input.requestId, { ...options, signal: new AbortController().signal }).catch(() => { });
        }
        throw error;
    }
    finally {
        if (abortListener)
            options.signal?.removeEventListener('abort', abortListener);
        parser?.finish();
        await releaseStream(reader, response, ended);
    }
}
/** Cancellation accepts its own signal and returns the server's actual state. */
export async function cancelChatRequest(commandRequestId, options) {
    const requestId = canonicalUuid(commandRequestId);
    options.signal?.throwIfAborted();
    const response = await (options.fetchImpl ?? fetch)(url(options.baseURL, `chat/requests/${encodeURIComponent(requestId)}/cancel`), {
        method: 'POST',
        headers: headers(options, false),
        signal: options.signal
    });
    await checkResponse(response, options);
    let body;
    try {
        body = await response.json();
    }
    catch {
        protocol('Invalid chat cancellation response');
    }
    if (!record(body) ||
        body.code !== 200 ||
        !record(body.data) ||
        body.data.requestId !== requestId ||
        typeof body.data.status !== 'string' ||
        (!terminalStatuses.has(body.data.status) && body.data.status !== 'completing')) {
        protocol('Invalid chat cancellation response');
    }
    return { requestId, status: body.data.status };
}
