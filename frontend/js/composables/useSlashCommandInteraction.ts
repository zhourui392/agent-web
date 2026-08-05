/**
 * useSlashCommandInteraction — 共享斜杠命令交互 composable
 *
 * 只负责命令过滤、弹窗、键盘导航、选择和 textarea 光标。
 * 通过 loadCommands 回调加载命令，不硬编码 /api/chat/commands、workingDir 或 DOM 选择器。
 *
 * @author alex
 * @since 2026-08-04
 */
import { ref, computed, nextTick, type Ref, type ComputedRef } from 'vue';

interface SlashCommand {
  name: string;
  description?: string;
  argumentHint?: string;
  [key: string]: unknown;
}

interface SlashCommandInteractionOptions {
  userInput: Ref<string>;
  loadCommands: () => Promise<SlashCommand[]>;
  onSubmit: () => void;
  textareaElement?: Ref<HTMLTextAreaElement | null>;
  focusTextarea?: () => void;
}

export function useSlashCommandInteraction(
  options: SlashCommandInteractionOptions,
): {
  slashCommands: Ref<SlashCommand[]>;
  showCommandPopup: Ref<boolean>;
  selectedCommandIdx: Ref<number>;
  filteredCommands: ComputedRef<SlashCommand[]>;
  loadSlashCommands: () => Promise<void>;
  handleEnter: () => void;
  handleArrowUp: () => void;
  handleArrowDown: () => void;
  handleTab: () => void;
  selectCommand: (cmd: SlashCommand) => void;
  hideCommandPopup: () => void;
} {
  const slashCommands = ref<SlashCommand[]>([]);
  const showCommandPopup = ref(false);
  const selectedCommandIdx = ref(0);

  const filteredCommands = computed(() => {
    const input = options.userInput.value;
    if (!input.startsWith('/')) return [];
    const spaceIdx = input.indexOf(' ');
    const query = spaceIdx > 0 ? input.substring(1, spaceIdx) : input.substring(1);
    if (!query) return slashCommands.value;
    return slashCommands.value.filter(c =>
      c.name.toLowerCase().includes(query.toLowerCase()),
    );
  });

  const loadSlashCommands = async () => {
    try {
      slashCommands.value = await options.loadCommands();
    } catch {
      slashCommands.value = [];
    }
  };

  const scrollCommandIntoView = () => {
    nextTick(() => {
      const popup = document.querySelector('.command-popup');
      if (!popup) return;
      const active = popup.querySelector('.command-item.active');
      if (active) active.scrollIntoView({ block: 'nearest' });
    });
  };

  const selectCommand = (cmd: SlashCommand) => {
    options.userInput.value = '/' + cmd.name + ' ';
    showCommandPopup.value = false;
    nextTick(() => {
      if (options.focusTextarea) {
        options.focusTextarea();
      } else if (options.textareaElement?.value) {
        options.textareaElement.value.focus();
      }
    });
  };

  const handleEnter = () => {
    if (showCommandPopup.value && filteredCommands.value.length > 0) {
      selectCommand(filteredCommands.value[selectedCommandIdx.value]);
    } else {
      options.onSubmit();
    }
  };

  const handleArrowUp = () => {
    if (!showCommandPopup.value) return;
    selectedCommandIdx.value = Math.max(0, selectedCommandIdx.value - 1);
    scrollCommandIntoView();
  };

  const handleArrowDown = () => {
    if (!showCommandPopup.value) return;
    selectedCommandIdx.value = Math.min(
      filteredCommands.value.length - 1,
      selectedCommandIdx.value + 1,
    );
    scrollCommandIntoView();
  };

  const handleTab = () => {
    if (showCommandPopup.value && filteredCommands.value.length > 0) {
      selectCommand(filteredCommands.value[selectedCommandIdx.value]);
    }
  };

  const hideCommandPopup = () => {
    showCommandPopup.value = false;
  };

  return {
    slashCommands,
    showCommandPopup,
    selectedCommandIdx,
    filteredCommands,
    loadSlashCommands,
    handleEnter,
    handleArrowUp,
    handleArrowDown,
    handleTab,
    selectCommand,
    hideCommandPopup,
  };
}