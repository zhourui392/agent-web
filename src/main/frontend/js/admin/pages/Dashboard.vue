<template>
  <admin-shell active="dashboard" @ready="onReady">
    <template #header-actions>
      <el-button text :loading="loading" @click="loadAll">刷新</el-button>
    </template>

    <div v-loading="loading" class="view-wrap">
      <el-row :gutter="16">
        <el-col v-for="kpi in kpis" :key="kpi.label" :span="6">
          <el-card class="kpi-card" shadow="hover">
            <div class="kpi-value">{{ kpi.value }}</div>
            <div class="kpi-label">{{ kpi.label }}</div>
          </el-card>
        </el-col>
      </el-row>

      <el-card class="mt16" shadow="never">
        <div class="section-title">近 {{ trend.length }} 天趋势</div>
        <div class="trend-legend">
          <span class="dot" style="background:#409eff;"></span>会话
        </div>
        <svg v-if="trendChart" :viewBox="'0 0 ' + chartW + ' ' + chartH" width="100%" :height="chartH" preserveAspectRatio="none">
          <line :x1="padL" :y1="chartH - padB" :x2="chartW - 8" :y2="chartH - padB" stroke="#ebeef5" />
          <polyline :points="trendChart.chatPts" fill="none" stroke="#409eff" stroke-width="2" />
          <text :x="padL" :y="chartH - 4" font-size="10" fill="#909399">{{ trendChart.firstDate }}</text>
          <text :x="chartW - 8" :y="chartH - 4" font-size="10" fill="#909399" text-anchor="end">{{ trendChart.lastDate }}</text>
          <text :x="padL - 4" :y="padT + 8" font-size="10" fill="#909399" text-anchor="end">{{ trendChart.maxV }}</text>
        </svg>
        <div v-else class="muted">暂无数据</div>
      </el-card>

      <el-row :gutter="16" class="mt16">
        <el-col v-for="dist in distributions" :key="dist.title" :span="8">
          <el-card shadow="never">
            <div class="section-title">{{ dist.title }}</div>
            <el-table :data="dist.rows" size="small" :show-header="false" empty-text="无数据">
              <el-table-column prop="key"></el-table-column>
              <el-table-column prop="count" align="right" width="80"></el-table-column>
            </el-table>
          </el-card>
        </el-col>
      </el-row>
    </div>
  </admin-shell>
</template>

<script>
import { ref, computed } from 'vue';
import { ElMessage } from 'element-plus';

export default {
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
        ElMessage.error('加载指标失败: ' + e);
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
};
</script>