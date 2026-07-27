<template>
  <div class="command-popup" v-if="commands.length > 0">
    <div v-for="(cmd, idx) in commands"
         :key="cmd.name"
         class="command-item" :class="{active: idx === selectedIdx}"
         @mousedown.prevent="$emit('select', cmd)">
      <span class="command-name">/{{ cmd.name }}</span>
      <span class="command-desc">{{ cmd.description || cmd.argumentHint }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
interface SlashCommand {
  name: string;
  description?: string;
  argumentHint?: string;
}

defineProps<{ commands: SlashCommand[]; selectedIdx: number }>();
defineEmits<{ (e: 'select', cmd: SlashCommand): void }>();
</script>