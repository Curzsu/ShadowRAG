export interface ChatRoundMessage {
  content: string;
  roundDraft?: string;
  intermediateRounds?: Array<{ roundId: number; content: string }>;
}

/** Transport validates order; keep displayed drafts separate from the confirmed final answer. */
export function applyChatRoundEvent(message: ChatRoundMessage, type: string, data: Record<string, unknown>) {
  if (type === 'chunk') {
    if (data.roundId === undefined) message.content += String(data.chunk);
    else message.roundDraft = (message.roundDraft ?? '') + String(data.chunk);
  } else if (type === 'round_end') {
    const content = message.roundDraft ?? '';
    if (data.kind === 'intermediate') {
      message.intermediateRounds ??= [];
      message.intermediateRounds.push({ roundId: Number(data.roundId), content });
    } else message.content = content;
    message.roundDraft = undefined;
  }
}
