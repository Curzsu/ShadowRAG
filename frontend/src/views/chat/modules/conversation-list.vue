<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router';
import { NButton, NPopconfirm, NScrollbar } from 'naive-ui';

defineOptions({
  name: 'ConversationList'
});

const route = useRoute();
const router = useRouter();
const chatStore = useChatStore();
const { conversationList, conversationId } = storeToRefs(chatStore);

// 页面加载时获取会话列表
onMounted(() => {
  chatStore.fetchConversationList();
});

/** 确保导航到聊天页面 */
async function ensureChatRoute() {
  if (route.name !== 'chat') {
    await router.push({ name: 'chat' });
  }
}

/** 新建对话 */
async function handleCreate() {
  await ensureChatRoute();
  await chatStore.createConversation();
}

/** 切换到指定会话 */
async function handleSwitch(convId: string) {
  await ensureChatRoute();
  if (convId === conversationId.value) return;
  await chatStore.switchConversation(convId);
}

/** 删除会话 */
async function handleDelete(convId: string) {
  await chatStore.deleteConversation(convId);
}

/** 格式化时间 */
function formatTime(timestamp?: string) {
  if (!timestamp) return '';
  const date = new Date(timestamp);
  const now = new Date();
  const isToday = date.toDateString() === now.toDateString();
  if (isToday) {
    return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
  }
  return date.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' });
}
</script>

<template>
  <div class="conversation-sidebar">
    <!-- 顶部：新建对话按钮 -->
    <div class="sidebar-header">
      <NButton type="primary" block @click="handleCreate">
        <template #icon>
          <icon-mdi-plus />
        </template>
        新建对话
      </NButton>
    </div>

    <!-- 会话列表 -->
    <NScrollbar class="sidebar-list">
      <div
        v-for="conv in conversationList"
        :key="conv.conversationId"
        class="conversation-item"
        :class="{ active: conv.conversationId === conversationId }"
        @click="handleSwitch(conv.conversationId)"
      >
        <div class="item-content">
          <div class="item-title">{{ conv.title || '新对话' }}</div>
          <div class="item-time">{{ formatTime(conv.updatedAt || conv.createdAt) }}</div>
        </div>
        <NPopconfirm @positive-click.stop="handleDelete(conv.conversationId)">
          <template #trigger>
            <NButton quaternary size="tiny" class="delete-btn" @click.stop>
              <template #icon>
                <icon-mdi-delete-outline />
              </template>
            </NButton>
          </template>
          确定删除此对话？
        </NPopconfirm>
      </div>

      <!-- 空状态 -->
      <div v-if="conversationList.length === 0" class="empty-state">
        <icon-mdi-chat-outline class="text-28px color-gray-300" />
        <p class="mt-2 text-12px color-gray-400">暂无对话记录</p>
      </div>
    </NScrollbar>
  </div>
</template>

<style scoped lang="scss">
.conversation-sidebar {
  @apply flex flex-col h-full w-full min-w-0 bg-transparent;

  .sidebar-header {
    @apply p-2.5 pb-2;
  }

  .sidebar-list {
    @apply flex-1 overflow-hidden min-h-0;
  }

  .conversation-item {
    @apply flex items-center justify-between px-2.5 py-2 mx-2 my-0.5 rounded-lg cursor-pointer transition-colors text-gray-700 dark:text-#d0d0d0;

    &:hover {
      @apply bg-gray-100 dark:bg-gray-800;

      .delete-btn {
        opacity: 1;
      }
    }

    &.active {
      @apply bg-blue-50/80 text-blue-600 font-medium dark:bg-blue-900/30 dark:text-blue-300;

      .item-title {
        @apply text-blue-700 font-medium dark:text-blue-300;
      }
    }

    .item-content {
      @apply flex-1 overflow-hidden min-w-0 mr-1;

      .item-title {
        @apply text-13px truncate leading-tight;
      }

      .item-time {
        @apply text-11px text-gray-400 dark:text-gray-500 mt-1;
      }
    }

    .delete-btn {
      opacity: 0;
      transition: opacity 0.2s;
    }
  }

  .empty-state {
    @apply flex flex-col items-center justify-center py-8;
  }
}
</style>
