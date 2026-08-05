<template>
  <div class="tool-block">
    <div class="tool-header" @click="$emit('toggle')">
      <span class="tool-toggle" :class="{expanded: expanded}">▶</span>
      <span class="tool-label">{{ displayName }}</span>
      <el-tag
        v-if="status"
        size="small"
        :type="statusType"
        class="tool-status-tag"
      >{{ status }}</el-tag>
      <span v-if="durationMs != null" class="tool-duration">{{ durationMs }}ms</span>
    </div>
    <div v-show="expanded" class="tool-content">
      <p v-if="commandSummary" class="tool-command-summary">{{ commandSummary }}</p>
      <p v-if="outputSummary" class="tool-output-summary">{{ outputSummary }}</p>
      <pre v-if="segment.content">{{ segment.content }}</pre>
      <dl v-if="hasMeta" class="tool-meta">
        <template v-if="repositoryKey"><dt>仓库</dt><dd>{{ repositoryKey }}</dd></template>
        <template v-if="commandClass"><dt>类型</dt><dd>{{ commandClass }}</dd></template>
        <template v-if="exitCode != null"><dt>退出码</dt><dd>{{ exitCode }}</dd></template>
      </dl>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';

interface ToolSegment {
  type: string;
  name: string;
  content: string;
}

const props = defineProps<{
  segment: ToolSegment;
  expanded: boolean;
  status?: string;
  durationMs?: number;
  commandSummary?: string;
  outputSummary?: string;
  repositoryKey?: string;
  commandClass?: string;
  exitCode?: number;
}>();

defineEmits<{ (e: 'toggle'): void }>();

const displayName = computed(() => props.repositoryKey
  ? `${props.repositoryKey}/${props.segment.name}`
  : props.segment.name);

const statusType = computed(() => {
  if (props.status === 'SUCCEEDED') return 'success';
  if (props.status === 'FAILED') return 'danger';
  return 'primary';
});

const hasMeta = computed(() =>
  Boolean(props.repositoryKey || props.commandClass || props.exitCode != null));
</script>