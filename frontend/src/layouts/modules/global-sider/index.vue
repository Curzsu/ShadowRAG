<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue';
import { GLOBAL_SIDER_MENU_ID } from '@/constants/app';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import GlobalLogo from '../global-logo/index.vue';

defineOptions({
  name: 'GlobalSider'
});

const appStore = useAppStore();
const themeStore = useThemeStore();

const isVerticalMix = computed(() => themeStore.layout.mode === 'vertical-mix');
const isHorizontalMix = computed(() => themeStore.layout.mode === 'horizontal-mix');
const darkMenu = computed(() => !themeStore.darkMode && !isHorizontalMix.value && themeStore.sider.inverted);
const showLogo = computed(() => !isVerticalMix.value && !isHorizontalMix.value);
const menuWrapperClass = computed(() => (showLogo.value ? 'flex-1-hidden' : 'h-full'));

// 侧边栏拖拽调整宽度
const isDragging = ref(false);
const DEFAULT_SIDER_WIDTH = 210;
const MIN_SIDER_WIDTH = 180;
const MAX_SIDER_WIDTH = 480;

function handleMouseDown(e: MouseEvent) {
  if (e.button !== 0 || appStore.siderCollapse || appStore.isMobile) return;
  e.preventDefault();

  const startX = e.clientX;
  const startWidth = themeStore.sider.width;
  isDragging.value = true;
  document.body.classList.add('is-resizing');

  function handleMouseMove(moveEvent: MouseEvent) {
    const deltaX = moveEvent.clientX - startX;
    let newWidth = Math.round(startWidth + deltaX);

    if (newWidth < MIN_SIDER_WIDTH) {
      newWidth = MIN_SIDER_WIDTH;
    } else if (newWidth > MAX_SIDER_WIDTH) {
      newWidth = MAX_SIDER_WIDTH;
    }

    themeStore.sider.width = newWidth;
  }

  function handleMouseUp() {
    isDragging.value = false;
    document.body.classList.remove('is-resizing');
    window.removeEventListener('mousemove', handleMouseMove);
    window.removeEventListener('mouseup', handleMouseUp);
  }

  window.addEventListener('mousemove', handleMouseMove);
  window.addEventListener('mouseup', handleMouseUp);
}

function handleResetWidth() {
  themeStore.sider.width = DEFAULT_SIDER_WIDTH;
}

onUnmounted(() => {
  document.body.classList.remove('is-resizing');
});
</script>

<template>
  <DarkModeContainer class="relative size-full flex-col-stretch shadow-sider" :inverted="darkMenu">
    <GlobalLogo
      v-if="showLogo"
      :show-title="!appStore.siderCollapse"
      :style="{ height: themeStore.header.height + 'px' }"
    />
    <div :id="GLOBAL_SIDER_MENU_ID" :class="menuWrapperClass"></div>

    <!-- 右侧边拖拽调节宽度手柄 -->
    <div
      v-if="!appStore.siderCollapse && !appStore.isMobile"
      class="sider-resizer"
      :class="{ 'is-resizing': isDragging }"
      title="拖拽调节宽度，双击恢复默认"
      @mousedown="handleMouseDown"
      @dblclick="handleResetWidth"
    >
      <div class="resizer-bar" />
    </div>
  </DarkModeContainer>
</template>

<style scoped lang="scss">
.sider-resizer {
  position: absolute;
  top: 0;
  right: 0;
  width: 6px;
  height: 100%;
  cursor: col-resize;
  z-index: 50;
  display: flex;
  justify-content: flex-end;
  user-select: none;

  .resizer-bar {
    width: 2px;
    height: 100%;
    opacity: 0;
    transition: opacity 0.2s;
    background-color: rgb(var(--primary-color, 100 108 255));
  }

  &:hover .resizer-bar,
  &.is-resizing .resizer-bar {
    opacity: 0.8;
  }
}
</style>
