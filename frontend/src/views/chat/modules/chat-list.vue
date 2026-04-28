<script setup lang="ts">
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

// 页面加载时：如果有当前会话则加载历史，否则自动创建
onMounted(async () => {
  chatStore.scrollToBottom = scrollToBottom;
  if (conversationId.value) {
    await chatStore.switchConversation(conversationId.value);
  } else {
    await chatStore.createConversation();
  }
});
</script>

<template>
  <Suspense>
    <NScrollbar ref="scrollbarRef" class="h-0 flex-auto">
      <div class="p-4">
        <VueMarkdownItProvider>
          <ChatMessage v-for="(item, index) in list" :key="index" :msg="item" />
        </VueMarkdownItProvider>

        <!-- 空状态 -->
        <div v-if="list.length === 0" class="flex flex-col items-center justify-center py-20 text-gray-400">
          <icon-mdi-chat-outline class="text-48px mb-4" />
          <p class="text-14px">开始新对话吧</p>
        </div>
      </div>
    </NScrollbar>
  </Suspense>
</template>

<style scoped lang="scss"></style>
