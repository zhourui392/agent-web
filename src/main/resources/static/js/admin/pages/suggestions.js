/**
 * 管理后台「用户建议」页。查看用户在主对话页提交的建议,并维护处理状态与回复。
 *
 * @author zhourui(V33215020)
 */
const { ref, reactive } = Vue;

bootstrapAdminApp({
  setup() {
    const statusOptions = [
      { value: 'PENDING', label: '待处理' },
      { value: 'PROCESSING', label: '处理中' },
      { value: 'REPLIED', label: '已回复' },
      { value: 'CLOSED', label: '已关闭' }
    ];
    const rows = ref([]);
    const total = ref(0);
    const page = ref(1);
    const size = ref(20);
    const status = ref('');
    const keyword = ref('');
    const loading = ref(false);
    const detailOpen = ref(false);
    const current = ref(null);
    const saving = ref(false);
    const isMobile = ref(window.innerWidth <= 768);
    const editForm = reactive({ status: 'PENDING', adminReply: '' });

    window.addEventListener('resize', () => { isMobile.value = window.innerWidth <= 768; });

    async function loadSuggestions() {
      loading.value = true;
      try {
        const params = new URLSearchParams({ page: page.value, size: size.value });
        if (status.value) {
          params.set('status', status.value);
        }
        if (keyword.value.trim()) {
          params.set('keyword', keyword.value.trim());
        }
        const data = await fetch('/api/admin-user-suggestions?' + params.toString()).then(r => r.json());
        rows.value = Array.isArray(data.rows) ? data.rows : [];
        total.value = data.total || 0;
      } catch (e) {
        ElementPlus.ElMessage.error('加载用户建议失败: ' + e);
      } finally {
        loading.value = false;
      }
    }

    function search() {
      page.value = 1;
      loadSuggestions();
    }

    function onPageChange(p) {
      page.value = p;
      loadSuggestions();
    }

    function openDetail(row) {
      current.value = row;
      editForm.status = row.status || 'PENDING';
      editForm.adminReply = row.adminReply || '';
      detailOpen.value = true;
    }

    async function saveCurrent() {
      if (!current.value) {
        return;
      }
      saving.value = true;
      try {
        const res = await fetch('/api/admin-user-suggestions/' + encodeURIComponent(current.value.id), {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            status: editForm.status,
            adminReply: editForm.adminReply
          })
        });
        if (!res.ok) {
          throw new Error(await res.text());
        }
        const updated = await res.json();
        current.value = updated;
        const idx = rows.value.findIndex(r => r.id === updated.id);
        if (idx >= 0) {
          rows.value.splice(idx, 1, updated);
        }
        ElementPlus.ElMessage.success('已保存');
        detailOpen.value = false;
      } catch (e) {
        ElementPlus.ElMessage.error('保存失败: ' + (e.message || '未知错误'));
      } finally {
        saving.value = false;
      }
    }

    function fmtTime(iso) {
      if (!iso) {
        return '-';
      }
      return String(iso).replace('T', ' ').replace(/\..*$/, '').replace('Z', '').slice(0, 19);
    }

    function statusType(s) {
      return {
        PENDING: 'warning',
        PROCESSING: 'primary',
        REPLIED: 'success',
        CLOSED: 'info'
      }[s] || 'info';
    }

    return {
      statusOptions,
      rows,
      total,
      page,
      size,
      status,
      keyword,
      loading,
      detailOpen,
      current,
      saving,
      isMobile,
      editForm,
      loadSuggestions,
      search,
      onPageChange,
      openDetail,
      saveCurrent,
      fmtTime,
      statusType
    };
  }
});
