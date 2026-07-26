<template>
  <div class="tool-block">
    <div class="tool-header" @click="$emit('toggle')">
      <span class="tool-toggle" :class="{expanded: expanded}">▶</span>
      <span class="tool-label">{{ segment.name }}</span>
    </div>
    <div v-show="expanded" class="tool-content">{{ segment.content }}</div>
  </div>
</template>

<script setup lang="ts">
interface ToolSegment {
  type: string;
  name: string;
  content: string;
}

defineProps<{ segment: ToolSegment; expanded: boolean }>();
defineEmits<{ (e: 'toggle'): void }>();
</script>