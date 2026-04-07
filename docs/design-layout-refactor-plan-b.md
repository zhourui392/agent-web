# 布局重构设计 — 方案 B

## 目标

将左侧栏从「堆叠所有功能」改为「对话为核心」的布局，低频全局设置移至顶栏，侧边栏只保留对话相关功能。

## 现状问题

左侧栏 320px 内堆了 6 个模块（环境切换、工作空间、分支切换、会话控制、历史对话、定时任务），挤压了对话区域的展示空间，且高低频功能混杂。

---

## 布局结构

```
┌──────────────────────────────────────────────────────────────────┐
│ TOPBAR                                                          │
│ [Logo]  | [环境切换] | [工作目录▾] [🌿 分支] |    [⏱ 定时任务] │
├────────────┬─────────────────────────────────────────────────────┤
│ SIDEBAR    │  CHAT AREA                                         │
│ 280px      │                                                    │
│            │  ┌─────────────────────────────────┐               │
│ [＋ 新对话] │  │ user: ...                       │               │
│            │  │ agent: ...                      │               │
│ ─── 今天   │  │ user: ...                       │               │
│ ▸ 对话1    │  │ agent: ...                      │               │
│ ▸ 对话2    │  │                                 │               │
│            │  └─────────────────────────────────┘               │
│ ─── 昨天   │                                                    │
│ ▸ 对话3    │  ┌─────────────────────────────────┐               │
│ ▸ 对话4    │  │ [输入框]                [发送]   │               │
│            │  └─────────────────────────────────┘               │
├────────────┴─────────────────────────────────────────────────────┤
```

---

## 模块迁移清单

| 模块 | 现位置 | 新位置 | 交互方式 |
|------|--------|--------|---------|
| 环境切换 | 侧边栏顶部 | **顶栏** | radio-group，保持原样 |
| 工作空间（目录+文件浏览+上传） | 侧边栏折叠卡片 | **顶栏** → 点击弹出 popover/dialog | 顶栏显示当前路径，点击打开弹窗操作 |
| 分支切换 | 侧边栏卡片（仅测试环境） | **顶栏** | 紧跟工作目录显示当前分支 tag，点击弹出 popover |
| 新对话 + 清除上下文 | 侧边栏卡片 | **侧边栏顶部** | 新对话按钮置顶，清除上下文移入对话区底部或顶栏 |
| 会话信息 | 侧边栏卡片内 | **移除** | sessionId/workingDir 等调试信息不再常驻显示 |
| 历史对话 | 侧边栏卡片 | **侧边栏主体** | 占满剩余空间，按日期分组 |
| 定时任务 | 侧边栏卡片 | **顶栏** → 点击弹出 dialog | 顶栏入口按钮，完整管理在弹窗内 |
| 定时任务编辑弹窗 | 侧边栏内 dialog | **保持** dialog | 不变，仍由弹窗触发 |
| 历史详情抽屉 | 侧边栏内 drawer | **保持** drawer | 不变 |

---

## 详细设计

### 1. 新增顶栏 `el-header`

在 `el-container` 顶部新增 `<el-header height="52px">`，取代原 `el-aside` 直接包裹 `el-main` 的结构。

**结构变更前：**
```html
<el-container style="height: 100%">
  <el-aside width="320px">...</el-aside>
  <el-main>...</el-main>
</el-container>
```

**结构变更后：**
```html
<el-container style="height: 100%">
  <el-header height="52px">
    <!-- 顶栏内容 -->
  </el-header>
  <el-container>
    <el-aside width="280px">
      <!-- 仅：新对话 + 历史列表 -->
    </el-aside>
    <el-main>...</el-main>
  </el-container>
</el-container>
```

### 2. 顶栏内容（从左到右）

```html
<el-header>
  ① Logo / 标题
  ② 分隔线
  ③ 环境切换 radio-group      ── 原 index.html:295-310 整段迁移
  ④ 分隔线
  ⑤ 工作目录选择器             ── 显示 currentPath，点击打开工作空间弹窗
  ⑥ 分支标签（仅 env=test）    ── 显示 currentBranch，点击打开分支弹窗
  ⑦ 右侧：定时任务按钮          ── 点击打开定时任务弹窗
</el-header>
```

#### 2.1 工作目录选择器

顶栏上显示为一个可点击的路径标签：

```html
<div class="workspace-selector" @click="workspaceDialogVisible = true">
  <el-icon><folder-opened /></el-icon>
  <span class="path">{{ currentPath || '选择工作目录' }}</span>
  <el-icon><arrow-down /></el-icon>
</div>
```

点击后弹出 `el-dialog`，内容为原侧边栏工作空间的全部内容（根路径选择、当前路径、文件列表、上传）。

**新增变量：**
```js
const workspaceDialogVisible = ref(false);
```

**迁移代码：** 原 index.html:313-376 的工作空间卡片内容移入 dialog body。

#### 2.2 分支切换

顶栏上显示当前分支 tag（仅 `env === 'test'` 时），点击弹出 popover 或 dialog：

```html
<template v-if="env === 'test'">
  <el-popover trigger="click" width="360">
    <template #reference>
      <el-tag type="success" style="cursor: pointer;">
        🌿 {{ currentBranch || '选择分支' }}
      </el-tag>
    </template>
    <!-- 原分支切换内容 index.html:388-410 迁移至此 -->
  </el-popover>
</template>
```

#### 2.3 定时任务入口

顶栏右侧放置一个按钮，点击打开 dialog：

```html
<el-button @click="taskManagerVisible = true">
  <el-icon><timer /></el-icon>
  定时任务
  <el-badge :value="taskList.length" v-if="taskList.length" />
</el-button>
```

**新增变量：**
```js
const taskManagerVisible = ref(false);
```

**迁移代码：** 原 index.html:478-535 的定时任务列表移入新的 `el-dialog`。原 index.html:538-572 的编辑弹窗保持不变（作为子弹窗嵌套）。

### 3. 侧边栏精简

宽度从 `320px` 缩减为 `280px`，仅包含两部分：

```html
<el-aside width="280px">
  <!-- 3.1 顶部：新对话按钮 -->
  <div class="sidebar-header">
    <el-button @click="newConversation" :loading="starting" :disabled="!currentPath" style="width: 100%;">
      ＋ 新对话
    </el-button>
  </div>

  <!-- 3.2 历史对话列表（占满剩余空间） -->
  <div class="history-list">
    <!-- 按日期分组 -->
    <div class="history-group" v-for="group in groupedHistory">
      <div class="history-group-title">{{ group.label }}</div>
      <div class="history-item" v-for="h in group.items" ...>
        ...
      </div>
    </div>
  </div>
</el-aside>
```

#### 3.1 新对话按钮

- 原「新对话」和「清除上下文」并排按钮 → 只保留「新对话」
- 「清除上下文」移到对话区输入框旁边（与停止按钮同行），或作为对话区顶部的小操作按钮

#### 3.2 历史对话按日期分组

新增 computed 属性，将 `historyList` 按日期分组：

```js
const groupedHistory = Vue.computed(() => {
  const now = new Date();
  const today = now.toDateString();
  const yesterday = new Date(now - 86400000).toDateString();
  const groups = { today: [], yesterday: [], older: [] };

  historyList.value.forEach(h => {
    const d = new Date(h.createdAt).toDateString();
    if (d === today) groups.today.push(h);
    else if (d === yesterday) groups.yesterday.push(h);
    else groups.older.push(h);
  });

  const result = [];
  if (groups.today.length) result.push({ label: '今天', items: groups.today });
  if (groups.yesterday.length) result.push({ label: '昨天', items: groups.yesterday });
  if (groups.older.length) result.push({ label: '更早', items: groups.older });
  return result;
});
```

#### 3.3 移除的内容

以下内容从侧边栏移除（迁移到顶栏或弹窗）：

| 原代码行 | 内容 | 去向 |
|----------|------|------|
| 295-310 | 环境切换 radio-group | 顶栏 |
| 313-376 | 工作空间卡片 | 顶栏 + 弹窗 |
| 379-419 | 分支切换卡片 | 顶栏 popover |
| 421-440 | 新对话/清除上下文/会话信息 | 侧边栏只保留新对话，其余移除或移入对话区 |
| 478-535 | 定时任务列表 | 顶栏 + 弹窗 |

### 4. 对话区调整

#### 4.1 清除上下文按钮位置

移到输入区底部操作栏，与「停止」「发送」同行：

```html
<div style="display: flex; gap: 8px;">
  <el-button @click="clearContext" :disabled="!sessionId" text>
    清除上下文
  </el-button>
  <span style="flex: 1;"></span>
  <el-button type="danger" @click="stopSession" v-if="sending" plain>停止</el-button>
  <el-button type="primary" @click="sendMessageStream" ...>发送</el-button>
</div>
```

### 5. 样式变更

#### 5.1 新增 CSS

```css
/* 顶栏 */
.topbar {
  display: flex;
  align-items: center;
  padding: 0 20px;
  gap: 16px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  box-shadow: 0 1px 4px rgba(0,0,0,0.04);
}
.topbar .logo {
  font-weight: 700;
  font-size: 15px;
  white-space: nowrap;
}
.topbar .divider {
  width: 1px;
  height: 24px;
  background: #dcdfe6;
}
.workspace-selector {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  color: #606266;
  background: #f5f7fa;
  max-width: 320px;
}
.workspace-selector:hover {
  border-color: #409eff;
}
.workspace-selector .path {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 侧边栏 */
.sidebar-header {
  padding: 16px;
  border-bottom: 1px solid #f0f0f0;
}
.history-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}
.history-group-title {
  font-size: 11px;
  color: #909399;
  padding: 12px 8px 6px;
  font-weight: 600;
}
```

#### 5.2 修改 CSS

```css
/* el-aside 宽度缩减 */
.el-aside {
  width: 280px;          /* 原 320px */
  display: flex;
  flex-direction: column; /* 使历史列表自动填充 */
}
```

### 6. 新增响应式变量汇总

```js
const workspaceDialogVisible = ref(false);  // 工作空间弹窗
const taskManagerVisible = ref(false);       // 定时任务管理弹窗
const branchPopoverVisible = ref(false);     // 分支切换 popover（如用 dialog 则同理）
```

### 7. return 语句更新

新增暴露：
```js
workspaceDialogVisible,
taskManagerVisible,
groupedHistory,
```

移除暴露（不再需要在模板中直接引用）：
```js
workspaceExpanded,  // 工作空间折叠状态不再需要，改为弹窗
```

---

## 实施步骤

按以下顺序分步改动，每步可独立验证：

### Step 1：搭建顶栏骨架
- 修改 `el-container` 结构，加入 `el-header`
- 顶栏先放 logo 和空占位
- 验证：页面布局不破坏，顶栏显示正常

### Step 2：环境切换迁移
- 将环境切换 radio-group 从侧边栏剪切到顶栏
- 删除侧边栏中的环境切换代码
- 验证：环境切换功能在顶栏正常工作

### Step 3：工作空间迁移
- 顶栏加工作目录选择器（路径显示 + 点击按钮）
- 新建 `el-dialog` 放工作空间内容（根路径、文件列表、上传）
- 删除侧边栏中的工作空间卡片
- 验证：目录浏览、路径切换、文件上传正常

### Step 4：分支切换迁移
- 顶栏加分支 tag 和 popover
- 迁移分支切换逻辑
- 删除侧边栏中的分支切换卡片
- 验证：分支切换、worktree 创建/清理正常

### Step 5：侧边栏精简
- 侧边栏宽度改为 280px
- 只保留新对话按钮 + 历史列表
- 历史列表加日期分组（groupedHistory computed）
- 移除会话信息显示
- 「清除上下文」移到对话区输入栏
- 验证：新对话、历史浏览、继续对话、删除历史正常

### Step 6：定时任务迁移
- 顶栏加定时任务按钮
- 新建管理弹窗，迁移任务列表 + 操作
- 编辑弹窗保持不变（嵌套 dialog）
- 删除侧边栏中的定时任务卡片
- 验证：任务 CRUD、启停、手动执行正常

### Step 7：样式收尾
- 调整顶栏间距、响应式
- 清理废弃的 CSS
- 删除 mockup.html

---

## 草图参考

交互式草图见 `mockup.html`（浏览器打开，可点击顶栏元素查看弹窗效果）。

## 不涉及的变更

- 后端 API 无需修改
- 对话区消息渲染逻辑不变
- 历史详情 drawer 不变
- 所有业务函数（switchBranch、loadTasks 等）逻辑不变，仅触发位置迁移
