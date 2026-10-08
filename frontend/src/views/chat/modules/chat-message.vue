<script setup lang="ts">
import { VueMarkdownIt } from 'vue-markdown-shiki';
import { formatDate } from '@/utils/common';
defineOptions({ name: 'ChatMessage' });

const props = defineProps<{ msg: Api.Chat.Message }>();

const authStore = useAuthStore();

function handleCopy(content: string) {
  navigator.clipboard.writeText(content);
  window.$message?.success('已复制');
}

const chatStore = useChatStore();

// 存储文件名和对应的事件处理
const sourceFiles = ref<Array<{ fileName: string; id: string }>>([]);

// 处理来源文件链接的函数
function processSourceLinks(text: string): string {
  // 匹配 (来源#数字: 文件名) 的正则表达式
  const sourcePattern = /\(来源#(\d+):\s*([^)]+)\)/g;

  return text.replace(sourcePattern, (_match, sourceNum, fileName) => {
    // 为文件名创建可点击的链接
    const linkClass = 'source-file-link';
    const encodedFileName = encodeURIComponent(fileName.trim());
    const fileId = `source-file-${sourceFiles.value.length}`;

    // 存储文件信息
    sourceFiles.value.push({
      fileName: encodedFileName,
      id: fileId
    });

    return `(来源#${sourceNum}: <span class="${linkClass}" data-file-id="${fileId}">${fileName}</span>)`;
  });
}

/**
 * 将裸 URL 包装为 markdown 链接，防止 linkify 插件误将中文标点后的文本纳入链接范围 例：（https://www.cnki.net/）的文本 →
 * （[https://www.cnki.net/](https://www.cnki.net/)）的文本
 */
function wrapBareUrls(text: string): string {
  return text.replace(
    /(?<![![(])(https?:\/\/[^\s<>"'（）【】《》「」""''、，。；：！？…·\u3000]+)/g,
    (_match, url: string) => {
      // 去掉末尾可能是句子标点而非 URL 组成部分的 ASCII 标点
      const cleaned = url.replace(/[.,;:!?)\]]+$/, '');
      return `[${cleaned}](${cleaned})`;
    }
  );
}

const content = computed(() => {
  chatStore.scrollToBottom?.();
  const rawContent = props.msg.roundDraft ?? props.msg.content ?? '';

  // 只对助手消息处理来源链接
  if (props.msg.role === 'assistant') {
    return processSourceLinks(wrapBareUrls(rawContent));
  }

  return rawContent;
});

// 处理内容点击事件（事件委托）
function handleContentClick(event: MouseEvent) {
  const target = event.target as HTMLElement;

  // 检查点击的是否是文件链接
  if (target.classList.contains('source-file-link')) {
    const fileId = target.getAttribute('data-file-id');
    if (fileId) {
      const file = sourceFiles.value.find(f => f.id === fileId);
      if (file) {
        handleSourceFileClick(file.fileName);
      }
    }
  }
}

// 处理来源文件点击事件
async function handleSourceFileClick(fileName: string) {
  const decodedFileName = decodeURIComponent(fileName);

  try {
    window.$message?.loading(`正在获取文件下载链接: ${decodedFileName}`, {
      duration: 0,
      closable: false
    });

    // 调用文件下载接口
    const { error, data } = await request<Api.Document.DownloadResponse>({
      url: 'documents/download',
      params: {
        fileName: decodedFileName
      },
      baseURL: '/proxy-api'
    });

    window.$message?.destroyAll();

    if (error) {
      window.$message?.error(`文件下载失败: ${error.response?.data?.message || '未知错误'}`);
      return;
    }

    if (data?.downloadUrl) {
      // 在新窗口打开下载链接
      window.open(data.downloadUrl, '_blank');
      window.$message?.success(`文件下载链接已打开: ${decodedFileName}`);
    } else {
      window.$message?.error('未能获取到下载链接');
    }
  } catch {
    window.$message?.destroyAll();
    window.$message?.error(`文件下载失败: ${decodedFileName}`);
  }
}
</script>

<template>
  <!-- 用户消息（admin）：头像与对话气泡靠右 -->
  <div v-if="msg.role === 'user'" class="mb-6 flex flex-row-reverse items-start gap-3">
    <!-- 用户头像（最右侧） -->
    <NAvatar round class="mt-0.5 flex-shrink-0 bg-success">
      <SvgIcon icon="ph:user-circle" class="text-icon-large color-white" />
    </NAvatar>

    <!-- 气泡主体区（靠右） -->
    <div class="max-w-[80%] min-w-0 flex flex-col items-end">
      <!-- 用户名与时间 -->
      <div class="mb-1 flex items-center gap-2 text-right">
        <NText class="text-3 color-gray-400">{{ formatDate(msg.timestamp) }}</NText>
        <NText class="text-3.5 text-gray-800 font-bold dark:text-gray-200">
          {{ authStore.userInfo.username || 'admin' }}
        </NText>
      </div>

      <!-- 对话气泡 -->
      <div class="user-bubble bg-primary text-white">
        {{ content }}
      </div>

      <!-- 操作栏（复制） -->
      <div class="mt-1 flex items-center justify-end gap-1 opacity-60 transition-opacity hover:opacity-100">
        <NButton quaternary size="tiny" @click="handleCopy(msg.content)">
          <template #icon>
            <icon-mynaui:copy />
          </template>
        </NButton>
      </div>
    </div>
  </div>

  <!-- AI助手消息（Brain.ai）：头像与回答靠左 -->
  <div v-else class="mb-6 flex items-start gap-3">
    <!-- Brain.ai 头像（最左侧） -->
    <NAvatar round class="mt-0.5 flex-shrink-0 bg-primary">
      <SystemLogo class="text-6 text-white" />
    </NAvatar>

    <!-- 消息主体区（靠左） -->
    <div class="min-w-0 flex flex-col flex-1 items-start">
      <!-- 助手名与时间 -->
      <div class="mb-1 flex items-center gap-2">
        <NText class="text-3.5 text-gray-800 font-bold dark:text-gray-200">Brain.ai</NText>
        <NText class="text-3 color-gray-400">{{ formatDate(msg.timestamp) }}</NText>
      </div>

      <!-- 等待中动画 -->
      <div v-if="msg.status === 'pending'" class="mt-1">
        <icon-eos-icons:three-dots-loading class="text-8 text-primary" />
      </div>

      <!-- 检索过程 -->
      <div v-if="msg.toolCalls?.length" class="mt-2 w-full" aria-label="工具调用记录" aria-live="polite">
        <div class="border border-gray-100 rounded-lg bg-gray-50 p-2.5 dark:border-gray-800 dark:bg-gray-800/50">
          <div class="text-3 color-gray-500 font-medium">工具调用 · {{ msg.toolCalls.length }} 次</div>
          <div
            v-for="(call, index) in msg.toolCalls"
            :key="call.callId"
            class="mt-2 flex flex-wrap items-center gap-x-2 gap-y-1 text-3 color-gray-500"
          >
            <span>第 {{ index + 1 }} 次 · 知识库检索</span>
            <span class="color-gray-400">第 {{ call.roundId }} 轮</span>
            <span v-if="call.status === 'finished'" class="color-success">已完成</span>
            <span v-else-if="['pending', 'loading', 'cancelling'].includes(msg.status || '')" class="color-primary">
              正在检索…
            </span>
            <span v-else>未完成</span>
          </div>
        </div>
      </div>
      <div v-if="msg.intermediateRounds?.some(round => round.content)" class="mt-2 w-full">
        <details class="border border-gray-100 rounded-lg bg-gray-50 p-2.5 dark:border-gray-800 dark:bg-gray-800/50">
          <summary class="cursor-pointer text-3 color-gray-500 font-medium">查看检索过程</summary>
          <div v-for="round in msg.intermediateRounds" :key="round.roundId" class="mt-2 text-3 color-gray-500">
            <VueMarkdownIt v-if="round.content" :content="round.content" />
          </div>
        </details>
      </div>

      <!-- 回答内容（Markdown） -->
      <div v-if="content" class="mt-1.5 w-full text-14px leading-relaxed" @click="handleContentClick">
        <VueMarkdownIt :content="content" />
      </div>

      <!-- 状态与进度提示 -->
      <NText
        v-if="
          !msg.toolCalls?.length && msg.toolProgress && ['pending', 'loading', 'cancelling'].includes(msg.status || '')
        "
        class="mt-2 text-3 color-gray-500"
      >
        {{ msg.toolProgress }}
      </NText>
      <NText v-if="msg.status === 'loading'" class="mt-1 text-3 color-gray-500">正在生成</NText>
      <NText v-if="msg.status === 'cancelling'" class="mt-1 text-3 color-gray-500">正在停止</NText>
      <NText v-if="msg.status === 'cancelled'" class="mt-1 text-3 color-gray-500">已停止</NText>
      <NText v-if="msg.status === 'finished'" class="mt-1 text-3 color-gray-400">已完成</NText>
      <NText v-if="msg.status === 'error'" class="mt-1 text-3 text-red-500 italic">
        {{ msg.errorReason || '生成失败，请重新发送' }}
      </NText>

      <!-- 底部操作与分割线 -->
      <div
        class="mt-2.5 w-full flex items-center justify-between border-t border-gray-100 pt-1.5 dark:border-gray-800/60"
      >
        <div class="flex items-center gap-2">
          <NButton
            quaternary
            size="tiny"
            class="opacity-60 transition-opacity hover:opacity-100"
            @click="handleCopy(msg.content)"
          >
            <template #icon>
              <icon-mynaui:copy />
            </template>
          </NButton>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.user-bubble {
  @apply max-w-full px-4 py-2.5 rounded-2xl rounded-tr-xs text-14px leading-relaxed shadow-sm break-words whitespace-pre-wrap;
  background-color: rgb(var(--primary-color, 100 108 255));
  color: #ffffff;
}

:deep(.source-file-link) {
  color: #1890ff;
  cursor: pointer;
  text-decoration: underline;
  transition: color 0.2s;

  &:hover {
    color: #40a9ff;
    text-decoration: none;
  }

  &:active {
    color: #096dd9;
  }
}
</style>
