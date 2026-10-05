<script setup lang="ts">
import { onBeforeRouteLeave } from 'vue-router';
import { NScrollbar } from 'naive-ui';
import { VueMarkdownItProvider } from 'vue-markdown-shiki';
import ChatMessage from './chat-message.vue';

defineOptions({
  name: 'ChatList'
});

const chatStore = useChatStore();
const { list, conversationId } = storeToRefs(chatStore);

const scrollbarRef = ref<InstanceType<typeof NScrollbar>>();

watch(() => [...list.value], scrollToBottom);

function scrollToBottom() {
  setTimeout(() => {
    scrollbarRef.value?.scrollBy({
      top: 999999999999999,
      behavior: 'auto'
    });
  }, 100);
}

// Empty views create their conversation on first send.
onMounted(async () => {
  chatStore.scrollToBottom = scrollToBottom;
  if (conversationId.value) {
    await chatStore.switchConversation(conversationId.value);
  }
});

function dispose() {
  chatStore.disposeActiveRequest('dispose');
  chatStore.scrollToBottom = null;
}
onBeforeRouteLeave(dispose);
onBeforeUnmount(dispose);
onDeactivated(dispose);
onActivated(() => {
  chatStore.scrollToBottom = scrollToBottom;
});
</script>

<template>
  <Suspense>
    <NScrollbar ref="scrollbarRef" class="h-0 flex-auto">
      <div class="p-4">
        <VueMarkdownItProvider>
          <ChatMessage v-for="item in list" :key="item.messageId" :msg="item" />
        </VueMarkdownItProvider>

        <!-- 空状态 -->
        <div v-if="list.length === 0" class="flex flex-col items-center justify-center py-20 text-gray-400">
          <icon-mdi-chat-outline class="mb-4 text-48px" />
          <p class="text-14px">开始新对话吧</p>
        </div>
      </div>
    </NScrollbar>
  </Suspense>
</template>

<style scoped lang="scss"></style>
