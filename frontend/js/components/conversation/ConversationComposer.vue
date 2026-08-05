<!--
  ConversationComposer — 共享对话输入骨架

  渲染 textarea、命令弹窗、发送和停止按钮，统一键盘行为。
  通过 attachments、left-actions、right-actions 和 status slot 扩展领域专属功能。
  不内置 Chat 图片上传、RAG 开关或清除上下文按钮。

  @author alex
  @since 2026-08-04
-->
<template>
  <div class="conversation-composer" style="position: relative;">
    <!-- 命令弹窗 -->
    <CommandPopup
      v-if="commandPopupVisible && commands.length > 0"
      :commands="commands"
      :selectedIdx="selectedCommandIndex"
      @select="cmd => $emit('select-command', cmd)"
    />
    <!-- 附件 slot（图片、文件、仓内文档等） -->
    <slot name="attachments" />
    <!-- textarea -->
    <el-input
      ref="textareaRef"
      :model-value="modelValue"
      type="textarea"
      :rows="textareaRows"
      :maxlength="maximumLength"
      show-word-limit
      resize="vertical"
      :placeholder="placeholder"
      :disabled="inputDisabled"
      :data-test="textareaDataTest"
      @update:model-value="val => $emit('update:modelValue', val)"
      @paste="onPaste"
      @keydown.enter.exact.prevent="onEnter"
      @keydown.up.prevent="onArrowUp"
      @keydown.down.prevent="onArrowDown"
      @keydown.tab.prevent="onTab"
      @keydown.escape="onEscape"
      @keydown.ctrl.enter.exact.prevent="insertNewline"
    />
    <!-- 操作栏 -->
    <div class="conversation-composer-actions">
      <div class="conversation-composer-left">
        <slot name="left-actions" />
      </div>
      <div class="conversation-composer-right">
        <slot name="status" />
        <slot name="right-actions" />
        <el-button
          v-if="runActive"
          type="danger"
          plain
          :loading="stopping"
          :disabled="stopDisabled !== undefined ? stopDisabled : (inputDisabled && !runActive)"
          :data-test="stopDataTest || 'conversation-stop'"
          @click="$emit('stop')"
        >
          停止
        </el-button>
        <el-button
          type="primary"
          :loading="submitting"
          :disabled="!canSubmit"
          :data-test="submitDataTest || 'conversation-submit'"
          @click="$emit('submit')"
        >
          发送
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick } from 'vue';
import CommandPopup from '../CommandPopup.vue';

interface SlashCommand {
  name: string;
  description?: string;
  argumentHint?: string;
}

const props = defineProps<{
  modelValue: string;
  placeholder: string;
  maximumLength: number;
  textareaRows: number;
  inputDisabled: boolean;
  canSubmit: boolean;
  submitting: boolean;
  runActive: boolean;
  stopping: boolean;
  commands: ReadonlyArray<SlashCommand>;
  commandPopupVisible: boolean;
  selectedCommandIndex: number;
  textareaDataTest?: string;
  stopDataTest?: string;
  submitDataTest?: string;
  stopDisabled?: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void;
  (e: 'submit'): void;
  (e: 'stop'): void;
  (e: 'paste-files', files: ReadonlyArray<File>): void;
  (e: 'select-command', command: SlashCommand): void;
  (e: 'arrow-up'): void;
  (e: 'arrow-down'): void;
  (e: 'escape'): void;
}>();

const textareaRef = ref<{ focus: () => void; $el: HTMLTextAreaElement } | null>(null);

function getTextAreaElement(): HTMLTextAreaElement | null {
  const el = textareaRef.value?.$el as any;
  return el?.querySelector?.('textarea') || el as any;
}

function onEnter() {
  if (props.commandPopupVisible && props.commands.length > 0) {
    const cmd = props.commands[props.selectedCommandIndex];
    if (cmd) {
      emit('select-command', cmd);
      return;
    }
  }
  if (props.canSubmit) emit('submit');
}

function onArrowUp() {
  emit('arrow-up');
}

function onArrowDown() {
  emit('arrow-down');
}

function onTab() {
  if (props.commandPopupVisible && props.commands.length > 0) {
    const cmd = props.commands[props.selectedCommandIndex];
    if (cmd) emit('select-command', cmd);
  }
}

function onEscape() {
  emit('escape');
}

function onPaste(event: ClipboardEvent) {
  const files = Array.from(event?.clipboardData?.files || [])
    .filter(file => file.type.startsWith('image/'));
  if (files.length > 0) {
    event.preventDefault();
    emit('paste-files', files);
  }
}

function insertNewline() {
  const ta = getTextAreaElement();
  if (!ta) return;
  const start = ta.selectionStart;
  const end = ta.selectionEnd;
  const value = props.modelValue;
  emit('update:modelValue', value.substring(0, start) + '\n' + value.substring(end));
  nextTick(() => { ta.selectionStart = ta.selectionEnd = start + 1; });
}

function focus() {
  const ta = getTextAreaElement();
  if (ta) ta.focus();
}

defineExpose({ focus, insertNewline });
</script>