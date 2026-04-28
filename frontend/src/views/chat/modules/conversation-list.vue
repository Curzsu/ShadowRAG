<script setup lang="ts">
import { NButton, NIcon, NScrollbar, NPopconfirm } from 'naive-ui';

defineOptions({
  name: 'ConversationList'
});

const chatStore = useChatStore();
const { conversationList, conversationId } = storeToRefs(chatStore);

// 页面加载时获取会话列表
onMounted(() => {
  chatStore.fetchConversationList();
});

/** 新建对话 */
async function handleCreate() {
  await chatStore.createConversation();
}

/** 切换到指定会话 */
async function handleSwitch(convId: string) {
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
        <icon-mdi-chat-outline class="text-32px color-gray-300" />
        <p class="text-12px color-gray-400 mt-2">暂无对话记录</p>
      </div>
    </NScrollbar>
  </div>
</template>

<style scoped lang="scss">
.conversation-sidebar {
  @apply flex flex-col h-full border-r border-gray-200 bg-gray-50;
  width: 240px;
  min-width: 240px;

  .sidebar-header {
    @apply p-3 border-b border-gray-200;
  }

  .sidebar-list {
    @apply flex-1 overflow-hidden;
  }

  .conversation-item {
    @apply flex items-center justify-between px-3 py-2.5 mx-2 my-0.5 rounded-md cursor-pointer transition-colors;

    &:hover {
      @apply bg-gray-100;

      .delete-btn {
        opacity: 1;
      }
    }

    &.active {
      @apply bg-blue-50 border-l-2 border-blue-500;

      .item-title {
        @apply text-blue-700 font-medium;
      }
    }

    .item-content {
      @apply flex-1 overflow-hidden;

      .item-title {
        @apply text-13px truncate;
      }

      .item-time {
        @apply text-11px text-gray-400 mt-0.5;
      }
    }

    .delete-btn {
      opacity: 0;
      transition: opacity 0.2s;
    }
  }

  .empty-state {
    @apply flex flex-col items-center justify-center py-10;
  }
}
</style>
