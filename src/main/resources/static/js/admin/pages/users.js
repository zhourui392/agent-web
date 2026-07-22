/**
 * 管理后台「用户管理」页。展示安全账号投影，并创建默认启用的新账号。
 *
 * @author zhourui(V33215020)
 */
const { ref, reactive, nextTick } = Vue;

bootstrapAdminApp({
  setup() {
    const users = ref([]);
    const loading = ref(false);
    const creating = ref(false);
    const createDialogOpen = ref(false);
    const createFormRef = ref(null);
    const createForm = reactive({ username: '', password: '', role: 'USER' });
    const createRules = {
      username: [
        { required: true, message: '请输入用户名', trigger: 'blur' },
        { max: 64, message: '用户名不能超过 64 个字符', trigger: 'blur' }
      ],
      password: [
        { required: true, message: '请输入初始密码', trigger: 'blur' },
        { min: 12, max: 256, message: '密码长度必须在 12 到 256 个字符之间', trigger: 'blur' }
      ],
      role: [{ required: true, message: '请选择角色', trigger: 'change' }]
    };

    async function loadUsers() {
      loading.value = true;
      try {
        const response = await fetch('/api/admin-users');
        if (!response.ok) {
          throw new Error('HTTP ' + response.status);
        }
        users.value = await response.json();
      } catch (error) {
        ElementPlus.ElMessage.error('加载用户失败: ' + error.message);
      } finally {
        loading.value = false;
      }
    }

    function openCreateDialog() {
      createForm.username = '';
      createForm.password = '';
      createForm.role = 'USER';
      createDialogOpen.value = true;
      nextTick(() => {
        if (createFormRef.value) {
          createFormRef.value.clearValidate();
        }
      });
    }

    async function createUser() {
      if (!createFormRef.value) {
        return;
      }
      try {
        await createFormRef.value.validate();
      } catch (validationError) {
        return;
      }

      creating.value = true;
      try {
        const response = await fetch('/api/admin-users', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(createForm)
        });
        if (!response.ok) {
          const errorBody = await response.json().catch(() => ({}));
          throw new Error(errorBody.error || errorBody.message || ('HTTP ' + response.status));
        }
        createDialogOpen.value = false;
        ElementPlus.ElMessage.success('用户创建成功');
        await loadUsers();
      } catch (error) {
        ElementPlus.ElMessage.error('创建用户失败: ' + error.message);
      } finally {
        creating.value = false;
      }
    }

    function roleLabel(role) {
      return role === 'ADMIN' ? '管理员' : '普通用户';
    }

    function formatTime(value) {
      if (!value) {
        return '-';
      }
      return new Date(value).toLocaleString('zh-CN', { hour12: false });
    }

    return {
      users,
      loading,
      creating,
      createDialogOpen,
      createFormRef,
      createForm,
      createRules,
      loadUsers,
      openCreateDialog,
      createUser,
      roleLabel,
      formatTime
    };
  }
});
