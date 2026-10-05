<script setup lang="ts">
const chatStore = useChatStore();
const { input, activeRequest, preparing, loadingHistory, list } = storeToRefs(chatStore);
const activeMessage = computed(() =>
  list.value.find(message => message.messageId === activeRequest.value?.assistantMessageId)
);
const isSending = computed(() => preparing.value || Boolean(activeRequest.value));
const isCancelling = computed(() => activeMessage.value?.status === 'cancelling');
const sendDisabled = computed(
  () =>
    loadingHistory.value ||
    isCancelling.value ||
    (!isSending.value && (!input.value.message.trim() || input.value.message.length > 16000))
);
const requestStatus = computed(() => {
  if (loadingHistory.value) return '正在加载会话';
  if (preparing.value) return '正在准备会话';
  if (isCancelling.value) return '正在停止';
  if (activeMessage.value?.status === 'pending') return '等待回答';
  if (isSending.value) return '正在生成';
  return input.value.message.length > 16000 ? '消息不能超过 16000 个字符' : '可以发送';
});
async function handleSend() {
  if (sendDisabled.value) return;
  if (isSending.value) await chatStore.stopGeneration();
  else await chatStore.sendMessage();
}
const inputRef = ref();
// 手动插入换行符（确保所有浏览器兼容）
const insertNewline = () => {
  const textarea = inputRef.value;
  const start = textarea.selectionStart;
  const end = textarea.selectionEnd;

  // 在光标位置插入换行符
  input.value.message = `${input.value.message.substring(0, start)}\n${input.value.message.substring(end)}`;

  // 更新光标位置（在插入的换行符之后）
  nextTick(() => {
    textarea.selectionStart = start + 1;
    textarea.selectionEnd = start + 1;
    textarea.focus(); // 确保保持焦点
  });
};

// ctrl + enter 换行
// enter 发送
const handShortcut = (e: KeyboardEvent) => {
  if (e.key === 'Enter' && !e.isComposing) {
    e.preventDefault();

    if (!e.shiftKey && !e.ctrlKey) {
      handleSend();
    } else insertNewline();
  }
};
</script>

<template>
  <div class="relative w-full b-1 b-#1c1c1c20 bg-#fff p-4 card-wrapper dark:bg-#1c1c1c">
    <textarea
      ref="inputRef"
      v-model="input.message"
      placeholder="给 Brain.ai 发送消息"
      class="min-h-10 w-full cursor-text resize-none b-none bg-transparent color-#333 caret-[rgb(var(--primary-color))] outline-none dark:color-#f1f1f1"
      @keydown="handShortcut"
    />
    <div class="flex items-center justify-between pt-2">
      <div class="flex items-center text-18px color-gray-500">
        <NText class="text-14px">{{ requestStatus }}</NText>
      </div>
      <NButton
        :disabled="sendDisabled"
        :aria-label="isSending ? '停止生成' : '发送消息'"
        strong
        circle
        type="primary"
        @click="handleSend"
      >
        <template #icon>
          <icon-material-symbols:stop-rounded v-if="isSending" />
          <icon-guidance:send v-else />
        </template>
      </NButton>
    </div>
  </div>
</template>

<style scoped></style>
