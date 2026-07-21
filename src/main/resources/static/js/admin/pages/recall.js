/**
 * Admin recall-observability page: RAG recall attempt metrics, filters, list,
 * and detail view.
 */
const { ref, reactive, computed } = Vue;
const RecallUtils = window.AgentRecallUtils;

bootstrapAdminApp({
  setup() {
    const loading = ref(false);
    const detailLoading = ref(false);
    const summary = ref(null);
    const attempts = ref([]);
    const chunks = ref([]);
    const total = ref(0);
    const page = ref(1);
    const pageSize = ref(20);
    const detailOpen = ref(false);
    const detail = ref(null);
    const dateRange = ref([]);
    const filters = reactive({
      status: '',
      sessionId: '',
      embeddingModel: '',
      env: '',
      sourceType: '',
      tier: '',
      from: null,
      to: null
    });

    function currentFilterParams() {
      return {
        status: filters.status,
        sessionId: filters.sessionId,
        embeddingModel: filters.embeddingModel,
        env: filters.env,
        sourceType: filters.sourceType,
        tier: filters.tier,
        from: filters.from,
        to: filters.to
      };
    }

    function summaryFilterParams() {
      return {
        from: filters.from,
        to: filters.to
      };
    }

    async function fetchJson(url) {
      const res = await fetch(url);
      if (!res.ok) {
        throw new Error(await res.text());
      }
      return res.json();
    }

    async function loadSummary() {
      const query = RecallUtils.buildRecallQuery(summaryFilterParams(), null, null);
      const url = query ? '/api/metrics/recall?' + query : '/api/metrics/recall';
      summary.value = await fetchJson(url);
    }

    async function loadAttempts() {
      const query = RecallUtils.buildRecallQuery(currentFilterParams(), page.value, pageSize.value);
      const data = await fetchJson('/api/metrics/recall-attempts?' + query);
      attempts.value = data.items || [];
      total.value = data.total || 0;
      page.value = data.page || page.value;
      pageSize.value = data.size || pageSize.value;
    }

    async function loadChunks() {
      const query = RecallUtils.buildRecallQuery(currentFilterParams(), null, null);
      const suffix = query ? '&' + query : '';
      chunks.value = await fetchJson('/api/metrics/recall-chunks?limit=50' + suffix);
    }

    async function loadAll() {
      loading.value = true;
      try {
        await Promise.all([loadSummary(), loadAttempts(), loadChunks()]);
      } catch (e) {
        ElementPlus.ElMessage.error('加载召回观测失败: ' + (e.message || '未知错误'));
      } finally {
        loading.value = false;
      }
    }

    function reloadFirstPage() {
      page.value = 1;
      loadAll();
    }

    function onPageChange(newPage) {
      page.value = newPage;
      loadAll();
    }

    function onSizeChange(newSize) {
      pageSize.value = newSize;
      page.value = 1;
      loadAll();
    }

    function onDateRangeChange(value) {
      filters.from = Array.isArray(value) && value.length === 2 ? Number(value[0]) : null;
      filters.to = Array.isArray(value) && value.length === 2 ? Number(value[1]) : null;
      reloadFirstPage();
    }

    function resetFilters() {
      filters.status = '';
      filters.sessionId = '';
      filters.embeddingModel = '';
      filters.env = '';
      filters.sourceType = '';
      filters.tier = '';
      filters.from = null;
      filters.to = null;
      dateRange.value = [];
      reloadFirstPage();
    }

    async function openDetail(id) {
      if (!id) {
        return;
      }
      detailOpen.value = true;
      detailLoading.value = true;
      detail.value = null;
      try {
        detail.value = await fetchJson('/api/metrics/recall-attempts/' + encodeURIComponent(id));
      } catch (e) {
        ElementPlus.ElMessage.error('加载召回详情失败: ' + (e.message || '未知错误'));
        detailOpen.value = false;
      } finally {
        detailLoading.value = false;
      }
    }

    const kpis = computed(() => {
      const s = summary.value;
      if (!s) {
        return [];
      }
      return [
        { label: '召回尝试', value: s.attemptCount },
        { label: '有效执行', value: s.executedCount },
        { label: '质量命中率', value: RecallUtils.pct(s.qualityHitRate) },
        { label: '0 命中率', value: RecallUtils.pct(s.noHitRate) },
        { label: '异常率', value: RecallUtils.pct(s.errorRate) },
        { label: '服务可用率', value: RecallUtils.pct(s.serviceAvailabilityRate) },
        { label: '平均命中数', value: typeof s.avgHitCount === 'number' ? s.avgHitCount.toFixed(2) : '-' },
        { label: '平均耗时', value: RecallUtils.millis(s.avgLatencyMs == null ? null : Math.round(s.avgLatencyMs)) }
      ];
    });

    const bucketRows = computed(() => {
      const s = summary.value || {};
      return []
        .concat((s.envBuckets || []).map(row => Object.assign({ group: 'env' }, row)))
        .concat((s.embeddingModelBuckets || []).map(row => Object.assign({ group: 'model' }, row)))
        .concat((s.sourceTypeBuckets || []).map(row => Object.assign({ group: 'source' }, row)))
        .concat((s.tierBuckets || []).map(row => Object.assign({ group: 'tier' }, row)));
    });

    const scoreSamples = computed(() => (summary.value && summary.value.scoreSamples) || []);

    function prettyJson(raw) {
      if (!raw) {
        return '-';
      }
      try {
        return JSON.stringify(JSON.parse(raw), null, 2);
      } catch (e) {
        return raw;
      }
    }

    function valueOrDash(value) {
      return value === null || value === undefined ? '-' : value;
    }

    return {
      onReady: loadAll,
      loading, detailLoading, summary, attempts, chunks, total, page, pageSize,
      detailOpen, detail, filters, dateRange, kpis, bucketRows, scoreSamples,
      loadAll, reloadFirstPage, onPageChange, onSizeChange, onDateRangeChange,
      resetFilters, openDetail, prettyJson, valueOrDash,
      pct: RecallUtils.pct,
      score: RecallUtils.score,
      millis: RecallUtils.millis,
      epochTime: RecallUtils.epochTime,
      statusLabel: RecallUtils.statusLabel,
      statusTagType: RecallUtils.statusTagType,
      bucketDisplayKey: RecallUtils.bucketDisplayKey,
      compactText: RecallUtils.compactText
    };
  }
});
