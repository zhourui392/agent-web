/**
 * 管理后台「需求事件」页: 跨需求事件检索(actor / 时间段过滤), 排查需求线状态迁移用。
 * 时间过滤转 epoch millis 传 /api/admin-requirement-events, 鉴权走管理口令 cookie。
 *
 * @author zhourui(V33215020)
 */
const { ref, reactive } = Vue;

bootstrapAdminApp({
  setup() {
    const events = ref([]);
    const loading = ref(false);
    const dateRange = ref([]);
    const filters = reactive({ actor: '' });

    async function search() {
      loading.value = true;
      try {
        const query = buildQuery();
        const res = await fetch('/api/admin-requirement-events' + (query ? '?' + query : ''));
        if (!res.ok) {
          throw new Error('HTTP ' + res.status);
        }
        const data = await res.json();
        events.value = Array.isArray(data) ? data : [];
      } catch (e) {
        ElementPlus.ElMessage.error('加载需求事件失败: ' + (e.message || e));
      } finally {
        loading.value = false;
      }
    }

    // el-date-picker value-format="x" 给出 epoch millis 字符串, 原样转 Number 传后端
    function buildQuery() {
      const params = new URLSearchParams();
      if (filters.actor && filters.actor.trim()) {
        params.set('actor', filters.actor.trim());
      }
      if (Array.isArray(dateRange.value) && dateRange.value.length === 2) {
        params.set('from', String(Number(dateRange.value[0])));
        params.set('to', String(Number(dateRange.value[1])));
      }
      return params.toString();
    }

    function fmtTime(ms) {
      if (!ms) {
        return '-';
      }
      const d = new Date(Number(ms));
      const p = n => String(n).padStart(2, '0');
      return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
        + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
    }

    return { events, loading, dateRange, filters, search, fmtTime };
  }
});
