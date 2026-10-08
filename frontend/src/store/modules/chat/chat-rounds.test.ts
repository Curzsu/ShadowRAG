import assert from 'node:assert/strict';
import { test } from 'node:test';
import { applyChatRoundEvent } from './chat-rounds';

test('drafts stream immediately and intermediate rounds never become the final answer', () => {
  const message = { content: '' } as Parameters<typeof applyChatRoundEvent>[0];
  applyChatRoundEvent(message, 'chunk', { roundId: 1, chunk: '先查A' });
  assert.equal(message.roundDraft, '先查A');
  assert.equal(message.content, '');
  applyChatRoundEvent(message, 'round_end', { roundId: 1, kind: 'intermediate' });
  assert.deepEqual(message.intermediateRounds, [{ roundId: 1, content: '先查A' }]);
  applyChatRoundEvent(message, 'chunk', { roundId: 2, chunk: '最终' });
  applyChatRoundEvent(message, 'chunk', { roundId: 2, chunk: '答案' });
  assert.equal(message.roundDraft, '最终答案');
  assert.equal(message.content, '');
  applyChatRoundEvent(message, 'round_end', { roundId: 2, kind: 'final' });
  assert.equal(message.content, '最终答案');
  assert.equal(message.roundDraft, undefined);
});
test('legacy chunks keep working and an empty final round is valid', () => {
  const legacy = { content: '' };
  applyChatRoundEvent(legacy, 'chunk', { chunk: 'old' });
  assert.equal(legacy.content, 'old');
  const empty = { content: '' };
  applyChatRoundEvent(empty, 'round_end', { roundId: 1, kind: 'final' });
  assert.equal(empty.content, '');
});

test('tool calls in consecutive rounds remain individually visible after the final answer', () => {
  const message = { content: '' } as Parameters<typeof applyChatRoundEvent>[0];
  const progress = (roundId: number, callId: string, status: string) =>
    applyChatRoundEvent(message, 'tool_progress', { roundId, callId, tool: 'search_knowledge_base', status });
  applyChatRoundEvent(message, 'round_end', { roundId: 1, kind: 'intermediate' });
  progress(1, 'person', 'started');
  progress(1, 'person', 'finished');
  applyChatRoundEvent(message, 'round_end', { roundId: 2, kind: 'intermediate' });
  progress(2, 'module', 'started');
  assert.deepEqual(message.toolCalls, [
    { roundId: 1, callId: 'person', tool: 'search_knowledge_base', status: 'finished' },
    { roundId: 2, callId: 'module', tool: 'search_knowledge_base', status: 'started' }
  ]);
  progress(2, 'module', 'finished');
  applyChatRoundEvent(message, 'chunk', { roundId: 3, chunk: '综合答案' });
  applyChatRoundEvent(message, 'round_end', { roundId: 3, kind: 'final' });
  assert.equal(message.content, '综合答案');
  assert.deepEqual(
    message.toolCalls?.map(call => [call.callId, call.status]),
    [
      ['person', 'finished'],
      ['module', 'finished']
    ]
  );
});

test('multiple tools in one round update their own rows without duplicating completion events', () => {
  const message = { content: '' } as Parameters<typeof applyChatRoundEvent>[0];
  for (const [callId, status] of [
    ['a', 'started'],
    ['b', 'started'],
    ['b', 'finished'],
    ['a', 'finished']
  ]) {
    applyChatRoundEvent(message, 'tool_progress', { roundId: 1, callId, tool: 'search_knowledge_base', status });
  }
  assert.deepEqual(
    message.toolCalls?.map(call => [call.roundId, call.callId, call.status]),
    [
      [1, 'a', 'finished'],
      [1, 'b', 'finished']
    ]
  );
});
