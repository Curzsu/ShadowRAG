<script setup lang="tsx">
import type { UploadFileInfo } from 'naive-ui';
import { NButton, NEllipsis, NModal, NPopconfirm, NProgress, NTag, NUpload } from 'naive-ui';
import { uploadAccept } from '@/constants/common';
import { fakePaginationRequest } from '@/service/request';
import { ParseStatus, UploadStatus } from '@/enum';
import SvgIcon from '@/components/custom/svg-icon.vue';
import FilePreview from '@/components/custom/file-preview.vue';
import UploadDialog from './modules/upload-dialog.vue';
import SearchDialog from './modules/search-dialog.vue';

const appStore = useAppStore();

// 文件预览相关状态
const previewVisible = ref(false);
const previewFileName = ref('');

// 列表接口本次请求是否失败：失败时保留页面上的任务与状态，不做任何同步
const fetchFailed = ref(false);

function apiFn() {
  return fakePaginationRequest<Api.KnowledgeBase.List>({ url: '/documents/uploads' }).then(result => {
    fetchFailed.value = Boolean(result.error);
    return result;
  });
}

function renderIcon(fileName: string) {
  const ext = getFileExt(fileName);
  if (ext) {
    if (uploadAccept.split(',').includes(`.${ext}`)) return <SvgIcon localIcon={ext} class="mx-4 text-12" />;
    return <SvgIcon localIcon="dflt" class="mx-4 text-12" />;
  }
  return null;
}

// 处理文件预览
function handleFilePreview(fileName: string) {
  previewFileName.value = fileName;
  previewVisible.value = true;
}

// 关闭文件预览
function closeFilePreview() {
  previewVisible.value = false;
  previewFileName.value = '';
}

const { columns, columnChecks, data, getData, loading } = useTable({
  apiFn,
  immediate: false,
  columns: () => [
    {
      key: 'fileName',
      title: '文件名',
      minWidth: 400,
      render: row => (
        <div class="flex items-center">
          {renderIcon(row.fileName)}
          <NEllipsis lineClamp={2} tooltip>
            <span
              class="cursor-pointer hover:text-primary transition-colors"
              onClick={() => handleFilePreview(row.fileName)}
            >
              {row.fileName}
            </span>
          </NEllipsis>
        </div>
      )
    },
    {
      key: 'totalSize',
      title: '文件大小',
      width: 100,
      render: row => fileSize(row.totalSize)
    },
    {
      key: 'status',
      title: '状态',
      width: 140,
      render: row => renderStatus(row)
    },
    {
      key: 'orgTagName',
      title: '组织标签',
      width: 150,
      ellipsis: { tooltip: true, lineClamp: 2 }
    },
    {
      key: 'isPublic',
      title: '是否公开',
      width: 100,
      render: row => (row.public || row.isPublic ? <NTag type="success">公开</NTag> : <NTag type="warning">私有</NTag>)
    },
    {
      key: 'createdAt',
      title: '上传时间',
      width: 100,
      render: row => dayjs(row.createdAt).format('YYYY-MM-DD')
    },
    {
      key: 'operate',
      title: '操作',
      width: 180,
      render: row => (
        <div class="flex gap-4">
          {renderResumeUploadButton(row)}
          <NButton
            type="primary"
            ghost
            size="small"
            onClick={() => handleFilePreview(row.fileName)}
          >
            预览
          </NButton>
          <NPopconfirm onPositiveClick={() => handleDelete(row.fileMd5)}>
            {{
              default: () => '确认删除当前文件吗？',
              trigger: () => (
                <NButton type="error" ghost size="small">
                  删除
                </NButton>
              )
            }}
          </NPopconfirm>
        </div>
      )
    }
  ]
});

const store = useKnowledgeBaseStore();
const { tasks } = storeToRefs(store);
onMounted(async () => {
  await getList();
});

// 后端 status=1 表示上传完成；status=0 但 mergedAt 不为空也视为已合并（并发竞态导致 status 未更新）
function isMerged(row: Api.KnowledgeBase.UploadTask) {
  return row.status === UploadStatus.Completed || Boolean(row.mergedAt);
}

// 解析状态是否未到终态：null/待处理(0)、处理中(1)、单次异常重试中(3) 都需要继续等待；2/4 为终态
function isParsePending(parseStatus: Api.KnowledgeBase.UploadTask['parseStatus']) {
  return parseStatus !== ParseStatus.Completed && parseStatus !== ParseStatus.DeadLetter;
}

// getList 是否进行中：轮询与手动刷新共用一个请求，避免重叠
let listLoading = false;

// 刚删除的文件MD5：短时间内同步时跳过，防止与删除并发返回的旧列表把该文件加回来
const deletedFileMd5s = new Set<string>();

/** 异步获取列表函数 该函数主要用于更新或初始化上传任务列表 它首先调用getData函数获取数据，然后根据获取到的数据状态更新任务列表 */
async function getList() {
  if (listLoading) return;
  listLoading = true;
  try {
    // 等待获取最新数据
    await getData();

    // 接口失败时 data 已被置空，保留页面现有任务与状态，等待下次刷新恢复
    if (fetchFailed.value) return;

    if (data.value.length === 0) {
      tasks.value = [];
      return;
    }

    // 遍历获取到的数据，以处理每个项目
    data.value.forEach(item => {
      // 刚删除的文件跳过，避免并发返回的旧数据把它加回来
      if (deletedFileMd5s.has(item.fileMd5)) return;

      // 查找任务列表中是否有匹配的文件MD5
      const index = tasks.value.findIndex(task => task.fileMd5 === item.fileMd5);
      // 检查项目是否已合并（上传完成）
      if (isMerged(item)) {
        // 如果找到匹配项，则同步服务端状态；本地上传中的 File、分片进度等字段保持不变
        if (index !== -1) {
          tasks.value[index].status = UploadStatus.Completed;
          tasks.value[index].parseStatus = item.parseStatus ?? null;
          tasks.value[index].mergedAt = item.mergedAt ?? tasks.value[index].mergedAt;
        } else {
          // 如果没有找到匹配项，确保 status 为 Completed 后添加到任务列表中
          item.status = UploadStatus.Completed;
          tasks.value.push(item);
        }
      } else if (index === -1) {
        // 如果项目状态不是已完成，并且任务列表中没有相同的文件MD5，则将该项目的状态设置为中断，并添加到任务列表中
        item.status = UploadStatus.Break;
        tasks.value.push(item);
      }
    });

    // 服务端已不存在的已合并任务（如已在其他入口删除）同步移除；
    // 上传中/待上传/中断的任务可能尚未在服务端建档，不能据此移除
    const serverFileMd5s = new Set(data.value.map(item => item.fileMd5));
    tasks.value = tasks.value.filter(task => !isMerged(task) || serverFileMd5s.has(task.fileMd5));
  } finally {
    listLoading = false;
  }
}

// #region 处理状态自动刷新
const PARSE_POLL_INTERVAL = 4000;
let pollTimer: ReturnType<typeof setTimeout> | null = null;
let pollDisposed = false;

// 存在已合并且解析状态未到终态的文件时自动刷新列表，全部到达终态后停止
const hasPendingParseTask = computed(() =>
  tasks.value.some(task => isMerged(task) && isParsePending(task.parseStatus))
);

watch(
  hasPendingParseTask,
  pending => {
    if (pending) ensurePolling();
    else stopPolling();
  },
  { immediate: true }
);

function ensurePolling() {
  if (pollTimer !== null) return;
  pollTimer = setTimeout(async () => {
    pollTimer = null;
    if (pollDisposed || !hasPendingParseTask.value) return;
    await getList();
    if (!pollDisposed && hasPendingParseTask.value) ensurePolling();
  }, PARSE_POLL_INTERVAL);
}

function stopPolling() {
  if (pollTimer !== null) {
    clearTimeout(pollTimer);
    pollTimer = null;
  }
}

onUnmounted(() => {
  pollDisposed = true;
  stopPolling();
});
// #endregion

async function handleDelete(fileMd5: string) {
  const index = tasks.value.findIndex(task => task.fileMd5 === fileMd5);

  if (index !== -1) {
    tasks.value[index].requestIds?.forEach(requestId => {
      request.cancelRequest(requestId);
    });
  }

  // 如果文件一个分片也没有上传完成，则直接删除
  if (tasks.value[index].uploadedChunks && tasks.value[index].uploadedChunks.length === 0) {
    tasks.value.splice(index, 1);
    return;
  }

  const { error } = await request({ url: `/documents/${fileMd5}`, method: 'DELETE' });
  if (!error) {
    tasks.value.splice(index, 1);
    deletedFileMd5s.add(fileMd5);
    // 超过两个轮询周期后服务端数据已稳定，无需继续跳过该文件
    setTimeout(() => deletedFileMd5s.delete(fileMd5), PARSE_POLL_INTERVAL * 2);
    window.$message?.success('删除成功');
    await getList();
  }
}

// #region 文件上传
const uploadVisible = ref(false);
function handleUpload() {
  uploadVisible.value = true;
}
// #endregion

// #region 检索知识库
const searchVisible = ref(false);
function handleSearch() {
  searchVisible.value = true;
}
// #endregion

// 渲染状态列：先判断上传是否完成，再判断解析状态
function renderStatus(row: Api.KnowledgeBase.UploadTask) {
  if (isMerged(row)) return renderParseStatus(row.parseStatus);
  if (row.status === UploadStatus.Break) return <NTag type="error">上传中断</NTag>;
  return <NProgress percentage={row.progress} processing />;
}

// 已合并文件的解析状态；文字可独立表达含义，颜色仅作辅助
function renderParseStatus(parseStatus: Api.KnowledgeBase.UploadTask['parseStatus']) {
  switch (parseStatus) {
    case ParseStatus.Completed:
      return <NTag type="success">处理完成</NTag>;
    case ParseStatus.DeadLetter:
      return <NTag type="error">处理失败</NTag>;
    case ParseStatus.Processing:
      return <NTag type="info">处理中</NTag>;
    case ParseStatus.Error:
      // parseStatus=3 只是单次处理异常，后台仍在重试；进入死信(4)才表示最终失败
      return <NTag type="info">处理中（重试中）</NTag>;
    default:
      // null/缺失/0：已合并但尚未开始解析
      return <NTag type="warning">上传完成，等待处理</NTag>;
  }
}

// #region 文件续传
function renderResumeUploadButton(row: Api.KnowledgeBase.UploadTask) {
  if (row.status === UploadStatus.Break) {
    if (row.file)
      return (
        <NButton type="primary" size="small" ghost onClick={() => resumeUpload(row)}>
          续传
        </NButton>
      );
    return (
      <NUpload
        show-file-list={false}
        default-upload={false}
        accept={uploadAccept}
        onBeforeUpload={options => onBeforeUpload(options, row)}
        class="w-fit"
      >
        <NButton type="primary" size="small" ghost>
          续传
        </NButton>
      </NUpload>
    );
  }
  return null;
}

// 任务列表存在文件，直接续传
function resumeUpload(row: Api.KnowledgeBase.UploadTask) {
  row.status = UploadStatus.Pending;
  store.startUpload();
}

async function onBeforeUpload(
  options: { file: UploadFileInfo; fileList: UploadFileInfo[] },
  row: Api.KnowledgeBase.UploadTask
) {
  const md5 = await calculateMD5(options.file.file!);
  if (md5 !== row.fileMd5) {
    window.$message?.error('两次上传的文件不一致');
    return false;
  }
  loading.value = true;
  const { error, data: progress } = await request<Api.KnowledgeBase.Progress>({
    url: '/upload/status',
    params: { file_md5: row.fileMd5 }
  });
  if (!error) {
    row.file = options.file.file!;
    row.status = UploadStatus.Pending;
    row.progress = progress.progress;
    row.uploadedChunks = progress.uploaded;
    store.startUpload();
    loading.value = false;
    return true;
  }
  loading.value = false;
  return false;
}
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard title="文件列表" :bordered="false" size="small" class="sm:flex-1-hidden card-wrapper">
      <template #header-extra>
        <TableHeaderOperation v-model:columns="columnChecks" :loading="loading" @add="handleUpload" @refresh="getList">
          <template #prefix>
            <NButton size="small" ghost type="primary" @click="handleSearch">
              <template #icon>
                <icon-ic-round-search class="text-icon" />
              </template>
              检索知识库
            </NButton>
          </template>
        </TableHeaderOperation>
      </template>
      <NDataTable
        striped
        :columns="columns"
        :data="tasks"
        size="small"
        :flex-height="!appStore.isMobile"
        :scroll-x="962"
        :loading="loading"
        remote
        :row-key="row => row.id"
        :pagination="false"
        class="sm:h-full"
      />
    </NCard>
    <UploadDialog v-model:visible="uploadVisible" />
    <SearchDialog v-model:visible="searchVisible" />
    
    <!-- 文件预览弹窗 -->
    <NModal v-model:show="previewVisible" preset="card" title="文件预览" style="width: 80%; max-width: 1000px;">
      <FilePreview
        :file-name="previewFileName"
        :visible="previewVisible"
        @close="closeFilePreview"
      />
    </NModal>
  </div>
</template>

<style scoped lang="scss">
.file-list-container {
  transition: width 0.3s ease;
}

:deep() {
  .n-progress-icon.n-progress-icon--as-text {
    white-space: nowrap;
  }
}
</style>
