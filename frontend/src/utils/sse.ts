export interface SseFrame {
  event: string;
  id: string;
  data: string;
}

export function createSseParser(
  onFrame: (frame: SseFrame) => void,
  maxEventChars = 262144
): {
  feed(bytes: Uint8Array): void;
  finish(): void;
} {
  if (!Number.isSafeInteger(maxEventChars) || maxEventChars < 1) throw new Error('Invalid SSE event buffer limit');
  const decoder = new TextDecoder('utf-8');
  let line = '';
  let event = '';
  let id = '';
  let data: string[] = [];
  let eventChars = 0;
  let skipLf = false;
  let finished = false;

  function countChar() {
    eventChars += 1;
    if (eventChars > maxEventChars) throw new Error('SSE event buffer limit exceeded');
  }

  function endLine() {
    if (line === '') {
      const frame = data.length ? { event: event || 'message', id, data: data.join('\n') } : undefined;
      event = '';
      id = '';
      data = [];
      eventChars = 0;
      if (frame) onFrame(frame);
      return;
    }
    countChar();
    const separator = line.indexOf(':');
    const field = separator === -1 ? line : line.slice(0, separator);
    let value = separator === -1 ? '' : line.slice(separator + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (field === 'event') event = value;
    if (field === 'id' && !value.includes('\0')) id = value;
    if (field === 'data') data.push(value);
    line = '';
  }

  return {
    feed(bytes) {
      if (finished) throw new Error('SSE parser is finished');
      const text = decoder.decode(bytes, { stream: true });
      for (let index = 0; index < text.length; index += 1) {
        const char = text[index];
        const ignoredLf = skipLf && char === '\n';
        if (skipLf) {
          skipLf = false;
          if (char === '\n') {
            if (eventChars) countChar();
          }
        }
        if (!ignoredLf && (char === '\r' || char === '\n')) {
          endLine();
          skipLf = char === '\r';
        } else if (!ignoredLf) {
          countChar();
          line += char;
        }
      }
    },
    finish() {
      if (finished) return;
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
