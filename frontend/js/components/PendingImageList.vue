<template>
  <div class="pending-image-list" v-if="images.length > 0">
    <div class="pending-image-card" v-for="(img, idx) in images" :key="img.path">
      <img :src="img.previewUrl" :alt="img.name" :title="img.name" />
      <span class="pending-image-remove" @click="$emit('remove', idx)">×</span>
    </div>
  </div>
</template>

<script setup lang="ts">
interface PendingImage {
  path: string;
  previewUrl: string;
  name: string;
}

defineProps<{ images: PendingImage[] }>();
defineEmits<{ (e: 'remove', idx: number): void }>();
</script>