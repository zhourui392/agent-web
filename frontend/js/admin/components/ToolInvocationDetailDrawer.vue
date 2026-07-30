<template>
  <el-drawer
    :model-value="open"
    title="工具调用详情"
    size="58%"
    direction="rtl"
    @update:model-value="$emit('update:open', $event)"
  >
    <div v-loading="loading" v-if="detail">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="Provider">{{ detail.provider || '—' }}</el-descriptions-item>
        <el-descriptions-item label="类别">{{ kindLabel(detail.invocationKind) }}</el-descriptions-item>
        <el-descriptions-item label="工具">{{ displayName }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ statusLabel(detail.status) }}</el-descriptions-item>
        <el-descriptions-item label="来源">{{ detail.source || '—' }}</el-descriptions-item>
        <el-descriptions-item label="触发来源">{{ detail.triggerSource || '—' }}</el-descriptions-item>
        <el-descriptions-item label="Run ID">{{ detail.runId || '—' }}</el-descriptions-item>
        <el-descriptions-item label="消息 ID">{{ detail.assistantMessageId || detail.sourceMessageId || '—' }}</el-descriptions-item>
        <el-descriptions-item label="开始时间">{{ formatTime(detail.startedAt) }}</el-descriptions-item>
        <el-descriptions-item label="完成时间">{{ formatTime(detail.completedAt) }}</el-descriptions-item>
        <el-descriptions-item label="Exit Code">{{ detail.exitCode == null ? '—' : detail.exitCode }}</el-descriptions-item>
        <el-descriptions-item label="迁移置信度">{{ detail.migrationConfidence == null ? '—' : detail.migrationConfidence }}</el-descriptions-item>
        <el-descriptions-item label="输入截断">{{ detail.inputTruncated ? '是' : '否' }}</el-descriptions-item>
        <el-descriptions-item label="输出截断">{{ detail.outputTruncated ? '是' : '否' }}</el-descriptions-item>
      </el-descriptions>
      <div class="section-title">输入</div>
      <pre class="tool-detail-pre">{{ prettyJson(detail.inputJson) }}</pre>
      <div class="section-title">输出</div>
      <pre class="tool-detail-pre">{{ detail.outputText || '—' }}</pre>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
defineProps<{
  open: boolean;
  loading?: boolean;
  detail: any;
  displayName?: string;
}>();

defineEmits<{ (event: 'update:open', value: boolean): void }>();

function kindLabel(value: string): string {
  return ({ TOOL_USE: '工具', COMMAND_EXECUTION: '命令执行', SKILL: 'Skill' } as Record<string, string>)[value] || value || '—';
}

function statusLabel(value: string): string {
  return ({ STARTED: '执行中', SUCCEEDED: '成功', FAILED: '失败', INCOMPLETE: '不完整', UNKNOWN: '未知' } as Record<string, string>)[value] || value || '—';
}

function formatTime(value: string | number | null | undefined): string {
  if (!value) return '—';
  return new Date(value).toLocaleString('zh-CN');
}

function prettyJson(value: string | null): string {
  if (!value) return '—';
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
}
</script>

<style scoped>
.section-title { font-weight: 600; margin-top: 16px; }
.tool-detail-pre { white-space: pre-wrap; word-break: break-all; max-height: 420px; overflow: auto; background: #f5f7fa; padding: 12px; }
</style>
