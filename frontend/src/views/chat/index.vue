<script setup lang="ts">
import ChatList from './modules/chat-list.vue';
import InputBox from './modules/input-box.vue';
</script>

<template>
  <div class="h-full min-w-0 flex flex-col gap-4">
    <ChatList />
    <InputBox />
  </div>
</template>

<style scoped></style>
