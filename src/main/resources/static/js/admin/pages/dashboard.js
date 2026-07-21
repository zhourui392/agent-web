/**
 * 管理后台「大盘」页(MPA)。/api/metrics/overview + /trend → KPI 卡 + 手绘 SVG 趋势 + 分布表。
 * 登录门 / 顶栏 / 侧栏由 AdminShell 承载;本页只管大盘数据。
 *
 * @author zhourui(V33215020)
 */
const { ref, computed } = Vue;

bootstrapAdminApp({
  setup() {
    const loading = ref(false);
    const overview = ref(null);
    const trend = ref([]);
    const trendDays = 30;
    const chartW = 760;
    const chartH = 200;
    const padL = 36;
    const padB = 22;
    const padT = 12;

    async function loadAll() {
      loading.value = true;
      try {
        const [o, t] = await Promise.all([
          fetch('/api/metrics/overview').then((r) => r.json()),
          fetch('/api/metrics/trend?days=' + trendDays).then((r) => r.json())
        ]);
        overview.value = o;
        trend.value = Array.isArray(t) ? t : [];
      } catch (e) {
        ElementPlus.ElMessage.error('加载指标失败: ' + e);
      } finally {
        loading.value = false;
      }
    }

    const pct = (v) => (v == null ? '—' : (v * 100).toFixed(1) + '%');
    const mapRows = (m) => (m ? Object.entries(m).map(([k, v]) => ({ key: k, count: v })) : []);

    const kpis = computed(() => {
      const o = overview.value;
      if (!o) {
        return [];
      }
      return [
        { label: '会话总量', value: o.chat.total },
        { label: 'AI 准确率(自评)', value: pct(o.chat.accuracyRate) }
      ];
    });

    const distributions = computed(() => {
      const o = overview.value;
      if (!o) {
        return [];
      }
      return [
        { title: '会话 · Agent 分布', rows: mapRows(o.chat.byAgentType) },
        { title: '会话 · 反馈分布', rows: mapRows(o.chat.feedback) }
      ];
    });

    const trendChart = computed(() => {
      const data = trend.value;
      if (!data || !data.length) {
        return null;
      }
      const n = data.length;
      const maxV = Math.max(1, ...data.map((p) => p.chatCount));
      const innerW = chartW - padL - 8;
      const innerH = chartH - padT - padB;
      const x = (i) => padL + (n === 1 ? 0 : (i * innerW) / (n - 1));
      const y = (v) => padT + innerH * (1 - v / maxV);
      const toPts = (key) => data.map((p, i) => x(i) + ',' + y(p[key])).join(' ');
      return {
        chatPts: toPts('chatCount'),
        maxV: maxV,
        firstDate: data[0].date,
        lastDate: data[n - 1].date
      };
    });

    return {
      onReady: loadAll,
      loading, overview, trend, chartW, chartH, padL, padB, padT,
      loadAll, kpis, distributions, trendChart
    };
  }
});
