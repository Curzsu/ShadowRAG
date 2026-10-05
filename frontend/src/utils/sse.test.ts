import assert from 'node:assert/strict';
import { test } from 'node:test';
import { type SseFrame, createSseParser } from './sse';

const encode = (text: string) => new TextEncoder().encode(text);

function parse(...reads: string[]) {
  const frames: SseFrame[] = [];
  const parser = createSseParser(frame => frames.push(frame));
  reads.forEach(read => parser.feed(encode(read)));
  parser.finish();
  return frames;
}

test('everyByteBoundaryPreservesUnicode', () => {
  const bytes = encode('event: chunk\nid: 2\ndata: 中文😀\n\n');
  for (let cut = 1; cut < bytes.length; cut += 1) {
    const frames: SseFrame[] = [];
    const parser = createSseParser(frame => frames.push(frame));
    parser.feed(bytes.slice(0, cut));
    parser.feed(bytes.slice(cut));
    parser.finish();
    assert.deepEqual(frames, [{ event: 'chunk', id: '2', data: '中文😀' }]);
  }
});

test('multipleEventsInOneRead', () => {
  assert.deepEqual(parse('data: first\n\nevent: chunk\nid: 2\ndata: second\n\n'), [
    { event: 'message', id: '', data: 'first' },
    { event: 'chunk', id: '2', data: 'second' }
  ]);
});

test('crlfAndCrAreSupported', () => {
  for (const separator of ['\n', '\r\n', '\r']) {
    const fixture = `event: chunk${separator}id: 1${separator}data: text${separator}${separator}`;
    for (let cut = 1; cut < fixture.length; cut += 1) {
      assert.deepEqual(parse(fixture.slice(0, cut), fixture.slice(cut)), [{ event: 'chunk', id: '1', data: 'text' }]);
    }
  }
});

test('multilineDataAndComments', () => {
  assert.deepEqual(
    parse(': ping\n\n: comment\nevent: chunk\nid: 4\nunknown: ignored\ndata: first\ndata:  second\ndata\n\n'),
    [{ event: 'chunk', id: '4', data: 'first\n second\n' }]
  );
});

test('incompleteEventAtEofIsDiscarded', () => {
  assert.deepEqual(parse('data: complete\n\ndata: half\n'), [{ event: 'message', id: '', data: 'complete' }]);
  assert.deepEqual(parse('data: half'), []);
});

test('bufferLimitIsEnforced', () => {
  const parser = createSseParser(() => {}, 12);
  parser.feed(encode('data: 123'));
  assert.throws(() => parser.feed(encode('4567')), /limit/i);
});

test('bufferLimitIncludesCompletedFieldsAndCommentsUntilBlankLine', () => {
  for (const field of [': 123\n', 'event: x\n', 'ignored: x\n', 'data: x\n']) {
    const parser = createSseParser(() => {}, field.length * 2);
    parser.feed(encode(field.repeat(2)));
    assert.throws(() => parser.feed(encode(field)), /limit/i);
  }
});

test('blankLinesResetLimitIncludingCommentOnlyFrames', () => {
  const parser = createSseParser(() => {}, 12);
  for (let index = 0; index < 100; index += 1) parser.feed(encode(': ping\n\n'));
  parser.feed(encode('data: x\n\n'));
  parser.finish();
});

test('limitCountsUtf16AndDoesNotCountTheNextEvent', () => {
  const frames: SseFrame[] = [];
  const parser = createSseParser(frame => frames.push(frame), 11);
  parser.feed(encode('data: 😀\n\ndata: 😀\n\n'));
  parser.finish();
  assert.equal(frames.length, 2);
});

test('crlfLineEndingsCountTowardsUnfinishedEventLimit', () => {
  const parser = createSseParser(() => {}, 8);
  parser.feed(encode(': x\r\n: '));
  assert.throws(() => parser.feed(encode('x\r\n')), /limit/i);
});

test('finishReleasesUnfinishedBufferAndStopsFurtherDispatch', () => {
  const frames: SseFrame[] = [];
  const parser = createSseParser(frame => frames.push(frame));
  parser.feed(encode('data: half'));
  parser.finish();
  parser.finish();
  assert.throws(() => parser.feed(encode('\n\n')), /finished/i);
  assert.deepEqual(frames, []);
});
