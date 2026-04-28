<script setup lang="ts">
import ConversationList from './modules/conversation-list.vue';
import ChatList from './modules/chat-list.vue';
import InputBox from './modules/input-box.vue';
</script>

<template>
  <div class="flex h-full">
    <ConversationList />
    <div class="flex-col gap-4 flex-1 min-w-0">
      <ChatList />
      <InputBox />
    </div>
  </div>
</template>

<style scoped></style>
