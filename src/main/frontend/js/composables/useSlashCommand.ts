/**
 * useSlashCommand composable: ChatPanel 命令弹窗交互切片(FE-R3.5)。
 *
 * 从 chat-panel.js setup 抽出: 命令状态(slashCommands/showCommandPopup/selectedCommandIdx) +
 * filteredCommands computed + loadSlashCommands/handleEnter/handleArrowUp/handleArrowDown/
 * handleTab/selectCommand/hideCommandPopup/scrollCommandIntoView。
 *
 * 与外部耦合: filteredCommands 读 userInput, selectCommand 写 userInput(组件状态);
 * loadSlashCommands 读 workingDir(props); handleEnter 在无命令选中时调 sendMessageStream
 * (resumable-run 切片,FE-R3.6)。故这四项以参数注入。
 *
 * 行为照搬 chat-panel.js 原内联实现,零逻辑变更。依赖 nextTick。
 */
import { ref, computed, nextTick, type Ref, type ComputedRef } from 'vue';

interface SlashCommand {
  name: string;
  [key: string]: unknown;
}

interface ChatIntegration {
  userInput: Ref<string>;
  workingDir: Ref<string>;
  sendMessageStream: () => void;
}

export function useSlashCommand(chat: ChatIntegration): {
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
    const input = chat.userInput.value;
    if (!input.startsWith('/')) return [];
    const query = input.indexOf(' ') > 0 ? input.substring(1, input.indexOf(' ')) : input.substring(1);
    if (!query) return slashCommands.value;
    return slashCommands.value.filter((c) => c.name.toLowerCase().includes(query.toLowerCase()));
  });

  const loadSlashCommands = async () => {
    if (!chat.workingDir.value) { slashCommands.value = []; return; }
    try {
      const cmds = await fetch('/api/chat/commands?workingDir=' + encodeURIComponent(chat.workingDir.value)).then((r) => r.json());
      slashCommands.value = cmds;
    } catch (e) {
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
    chat.userInput.value = '/' + cmd.name + ' ';
    showCommandPopup.value = false;
    nextTick(() => {
      const textarea = document.querySelector('.chat-input-area textarea') as HTMLTextAreaElement | null;
      if (textarea) textarea.focus();
    });
  };

  const handleEnter = () => {
    if (showCommandPopup.value && filteredCommands.value.length > 0) {
      selectCommand(filteredCommands.value[selectedCommandIdx.value]);
    } else {
      chat.sendMessageStream();
    }
  };

  const handleArrowUp = () => {
    if (!showCommandPopup.value) return;
    selectedCommandIdx.value = Math.max(0, selectedCommandIdx.value - 1);
    scrollCommandIntoView();
  };

  const handleArrowDown = () => {
    if (!showCommandPopup.value) return;
    selectedCommandIdx.value = Math.min(filteredCommands.value.length - 1, selectedCommandIdx.value + 1);
    scrollCommandIntoView();
  };

  const handleTab = () => {
    if (showCommandPopup.value && filteredCommands.value.length > 0) {
      selectCommand(filteredCommands.value[selectedCommandIdx.value]);
    }
  };

  const hideCommandPopup = () => { showCommandPopup.value = false; };

  return {
    slashCommands, showCommandPopup, selectedCommandIdx, filteredCommands,
    loadSlashCommands, handleEnter, handleArrowUp, handleArrowDown,
    handleTab, selectCommand, hideCommandPopup,
  };
}