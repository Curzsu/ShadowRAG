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
