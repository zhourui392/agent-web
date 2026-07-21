/**
 * 需求看板(M0+M2): 列表 REST 轮询 15s + 动作后即时刷新; 合法动作按钮组按状态渲染,
 * 与后端状态机迁移表(detailed-design §1.2)同表——按钮只是入口, 合法性由后端裁决。
 * M2 增补: plan/implement/verify run 发起(202 异步)、交付草稿 MR、MR 列表、run-stream SSE 输出。
 *
 * @author zhourui(V33215020)
 */
(function () {
  const { createApp } = Vue;
  const { ElMessage, ElMessageBox } = ElementPlus;

  const STATUS_LABELS = {
    INTAKE: '待接入', PLANNED: '已计划', APPROVED: '已批准', IMPLEMENTING: '实现中',
    VERIFYING: '验证中', REVIEW: '评审中', DELIVERED: '已交付', SUSPENDED: '已挂起', ARCHIVED: '已归档',
  };

  // 状态 → 前端可见动作(与后端迁移表同源, 后端仍是唯一裁决者)
  // M2 run 动作: PLAN_RUN(AI 生成计划) / IMPLEMENT_RUN / FIX_RUN(退回后修复) / VERIFY_RUN / DELIVER_DRAFT
  const STATUS_ACTIONS = {
    INTAKE: ['ATTACH_PLAN', 'PLAN_RUN', 'SUSPEND', 'ARCHIVE'],
    PLANNED: ['ATTACH_PLAN', 'PLAN_RUN', 'REJECT_PLAN', 'APPROVE', 'SUSPEND', 'ARCHIVE'],
    APPROVED: ['START_IMPLEMENT', 'IMPLEMENT_RUN', 'SUSPEND', 'ARCHIVE'],
    IMPLEMENTING: ['START_VERIFY', 'FIX_RUN', 'VERIFY_RUN', 'DELIVER_DRAFT', 'SUSPEND', 'ARCHIVE'],
    VERIFYING: ['SUSPEND', 'ARCHIVE'],
    REVIEW: ['MARK_DELIVERED', 'REQUEST_CHANGES', 'DELIVER_DRAFT', 'SUSPEND', 'ARCHIVE'],
    SUSPENDED: ['RESUME', 'ARCHIVE'],
    DELIVERED: [],
    ARCHIVED: [],
  };

  // 计划 run 轮询: 3s 一轮, 最多 20 轮(~60s)
  const PLAN_POLL_INTERVAL_MILLIS = 3000;
  const PLAN_POLL_MAX_ATTEMPTS = 20;

  async function api(path, options) {
    const resp = await fetch(path, options);
    if (resp.status === 204) { return null; }
    const body = await resp.json().catch(() => ({}));
    if (!resp.ok) {
      const message = body.code ? body.code + ': ' + (body.message || '') : (body.error || resp.status);
      const error = new Error(message);
      // 业务错误码(如 CREDENTIAL_INSUFFICIENT)供调用方按码分支提示
      error.code = body.code;
      throw error;
    }
    return body;
  }

  function postJson(path, payload) {
    return api(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload || {}),
    });
  }

  createApp({
    data() {
      return {
        columns: Object.keys(STATUS_LABELS).map((key) => ({ key, label: STATUS_LABELS[key] })),
        items: [],
        createDialog: { visible: false, title: '', description: '' },
        inbox: { visible: false, items: [] },
        drawer: { visible: false, tab: 'overview', detail: null, events: [], planDraft: '', mrs: [], rounds: [], runOutput: '' },
        pollTimer: null,
        planPollTimer: null,
        runStreamSource: null,
      };
    },
    mounted() {
      this.load();
      this.pollTimer = setInterval(() => this.load(), 15000);
    },
    beforeUnmount() {
      clearInterval(this.pollTimer);
      this.stopPlanPoll();
      this.closeRunStream();
    },
    methods: {
      statusLabel(status) { return STATUS_LABELS[status] || status; },
      itemsOf(status) { return this.items.filter((item) => item.status === status); },
      can(action) {
        const detail = this.drawer.detail;
        return detail && (STATUS_ACTIONS[detail.status] || []).includes(action);
      },
      goBack() { window.location.href = 'index.html'; },

      async load() {
        try {
          this.items = await api('api/requirements');
        } catch (e) {
          // 轮询失败静默, 避免刷屏; 手动操作路径有独立报错
        }
      },

      openCreate() {
        this.createDialog = { visible: true, title: '', description: '' };
      },
      async submitCreate() {
        try {
          await postJson('api/requirements', {
            title: this.createDialog.title,
            description: this.createDialog.description,
          });
          this.createDialog.visible = false;
          ElMessage.success('需求已创建');
          await this.load();
        } catch (e) {
          ElMessage.error(e.message);
        }
      },

      async openDetail(id) {
        this.stopPlanPoll();
        this.closeRunStream();
        try {
          this.drawer.detail = await api('api/requirements/' + id);
          this.drawer.events = await api('api/requirements/' + id + '/events');
          this.drawer.planDraft = '';
          this.drawer.runOutput = '';
          this.drawer.tab = 'overview';
          this.drawer.visible = true;
          await this.loadMrs(id);
        } catch (e) {
          ElMessage.error(e.message);
        }
      },
      async refreshDetail() {
        const id = this.drawer.detail.id;
        this.drawer.detail = await api('api/requirements/' + id);
        this.drawer.events = await api('api/requirements/' + id + '/events');
        await this.loadMrs(id);
        await this.load();
      },
      async loadMrs(id) {
        try {
          this.drawer.mrs = await api('api/requirements/' + id + '/merge-requests');
        } catch (e) {
          // MR 列表拿不到不阻断抽屉主流程
          this.drawer.mrs = [];
        }
        try {
          this.drawer.rounds = await api('api/requirements/' + id + '/verification-rounds');
        } catch (e) {
          this.drawer.rounds = [];
        }
      },

      async act(action, payload) {
        try {
          await postJson('api/requirements/' + this.drawer.detail.id + '/' + action, payload);
          ElMessage.success('操作成功');
          await this.refreshDetail();
        } catch (e) {
          ElMessage.error(e.message);
        }
      },
      async submitPlan() {
        await this.act('plan', { planText: this.drawer.planDraft });
        this.drawer.tab = 'overview';
      },
      approve() { return this.act('approve'); },
      rejectPlan(reason) { return this.act('reject-plan', { reason }); },
      suspendReq(reason) { return this.act('suspend', { reason }); },
      requestChanges(reason) { return this.act('request-changes', { reason }); },
      archiveReq() {
        ElMessageBox.confirm('归档后不可恢复, 确认归档?', '归档需求', { type: 'warning' })
          .then(() => this.act('archive', {}))
          .catch(() => {});
      },
      promptAction(label, handler) {
        ElMessageBox.prompt(label, { inputPlaceholder: label })
          .then(({ value }) => handler.call(this, value))
          .catch(() => {});
      },

      // ---- M2: run 发起(后端 202 异步)与交付草稿 MR ----

      async planRun() {
        const id = this.drawer.detail.id;
        const baseline = { status: this.drawer.detail.status, planText: this.drawer.detail.planText || '' };
        try {
          await postJson('api/requirements/' + id + '/plan-run');
        } catch (e) {
          ElMessage.error(e.message);
          return;
        }
        ElMessage.info('计划生成中, 完成后自动刷新');
        this.openRunStream(id);
        this.startPlanPoll(id, baseline);
      },
      // 轮询详情直到计划就绪: INTAKE 出发看状态变 PLANNED; 已 PLANNED 出发看 planText 变化
      startPlanPoll(id, baseline) {
        this.stopPlanPoll();
        let attempts = 0;
        this.planPollTimer = setInterval(async () => {
          attempts += 1;
          if (attempts > PLAN_POLL_MAX_ATTEMPTS) {
            this.stopPlanPoll();
            ElMessage.warning('计划生成超时, 请稍后手动刷新');
            return;
          }
          if (!this.drawer.visible || !this.drawer.detail || this.drawer.detail.id !== id) {
            this.stopPlanPoll();
            return;
          }
          try {
            const detail = await api('api/requirements/' + id);
            const planReady = detail.status === 'PLANNED'
              && (baseline.status !== 'PLANNED' || (detail.planText || '') !== baseline.planText);
            if (planReady) {
              this.stopPlanPoll();
              await this.refreshDetail();
              ElMessage.success('计划已生成');
            }
          } catch (e) {
            // 单轮查询失败静默, 等下一轮
          }
        }, PLAN_POLL_INTERVAL_MILLIS);
      },
      stopPlanPoll() {
        clearInterval(this.planPollTimer);
        this.planPollTimer = null;
      },

      async implementRun() {
        await this.startRun('implement-run', '实现 run 已发起');
      },
      async fixRun() {
        await this.startRun('fix-run', '修复 run 已发起');
      },
      async verifyRun() {
        await this.startRun('verify-run', '验证 run 已发起');
      },
      async startRun(action, successText) {
        const id = this.drawer.detail.id;
        try {
          await postJson('api/requirements/' + id + '/' + action);
          ElMessage.success(successText);
          this.openRunStream(id);
          await this.refreshDetail();
        } catch (e) {
          ElMessage.error(e.message);
        }
      },

      async deliverDraft() {
        try {
          const mr = await postJson('api/requirements/' + this.drawer.detail.id + '/deliver-draft');
          await this.refreshDetail();
          this.showMrLink(mr);
        } catch (e) {
          if (e.code === 'CREDENTIAL_INSUFFICIENT') {
            ElMessage.error('Git 凭证未配置, 请到 系统设置 → Git 配置 补齐后重试');
          } else {
            ElMessage.error(e.message);
          }
        }
      },
      showMrLink(mr) {
        ElMessageBox.alert(
          Vue.h('a', { href: mr.mrUrl, target: '_blank', rel: 'noopener' }, mr.mrUrl),
          '草稿 MR 已创建',
          { confirmButtonText: '知道了' }
        );
      },

      // run-stream SSE: chunk 追加到抽屉底部可折叠输出区; 断开/错误静默关闭(不自动重连)
      openRunStream(id) {
        this.closeRunStream();
        this.drawer.runOutput = '';
        try {
          const source = new EventSource('api/requirements/' + id + '/run-stream');
          source.addEventListener('chunk', (event) => {
            this.drawer.runOutput += event.data + '\n';
          });
          source.addEventListener('error', () => this.closeRunStream());
          source.onerror = () => this.closeRunStream();
          this.runStreamSource = source;
        } catch (e) {
          // SSE 不可用静默降级, 不影响 run 发起
        }
      },
      closeRunStream() {
        if (this.runStreamSource) {
          this.runStreamSource.close();
          this.runStreamSource = null;
        }
      },
      onDrawerClose() {
        this.stopPlanPoll();
        this.closeRunStream();
      },

      formatTime(epochMillis) {
        return epochMillis ? new Date(epochMillis).toLocaleString('zh-CN', { hour12: false }) : '';
      },

      // ---- M4: 知识收件箱(候选人工审批, 批准经 issue-log 通道落盘需求 worktree) ----

      async openInbox() {
        this.inbox.visible = true;
        await this.loadInbox();
      },
      async loadInbox() {
        try {
          this.inbox.items = await api('api/knowledge-suggestions?status=PENDING');
        } catch (e) {
          ElMessage.error(e.message);
        }
      },
      editSignals(suggestion) {
        ElMessageBox.prompt('触发词(逗号分隔): 错误码/报错原文/用户症状短语', '补触发词', {
          inputValue: suggestion.triggerSignals.join(','),
        }).then(async ({ value }) => {
          const signals = (value || '').split(/[,，]/).map((t) => t.trim()).filter(Boolean);
          try {
            await api('api/knowledge-suggestions/' + suggestion.id, {
              method: 'PUT',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({
                title: suggestion.title,
                triggerSignals: signals,
                phenomenon: suggestion.phenomenon,
                rootCause: suggestion.rootCause,
                solution: suggestion.solution,
                notes: suggestion.notes,
              }),
            });
            await this.loadInbox();
          } catch (e) {
            ElMessage.error(e.message);
          }
        }).catch(() => {});
      },
      async approveSuggestion(suggestion) {
        try {
          const result = await postJson('api/knowledge-suggestions/' + suggestion.id + '/approve');
          ElMessage.success('已落盘 ' + result.issueId + ', 请到需求工作区 review 后随 MR 提交');
          await this.loadInbox();
        } catch (e) {
          ElMessage.error(e.message);
        }
      },
      rejectSuggestion(suggestion) {
        ElMessageBox.prompt('拒绝原因', '拒绝知识候选')
          .then(async ({ value }) => {
            try {
              await postJson('api/knowledge-suggestions/' + suggestion.id + '/reject', { reason: value });
              await this.loadInbox();
            } catch (e) {
              ElMessage.error(e.message);
            }
          })
          .catch(() => {});
      },
    },
  }).use(ElementPlus).mount('#app');
})();
