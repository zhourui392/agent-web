<template>
  <admin-shell active="users" @ready="loadUsers">
    <template #header-actions>
      <el-button text @click="loadUsers" :loading="loading">刷新</el-button>
      <el-button type="primary" @click="openCreateDialog">新增用户</el-button>
    </template>

    <div class="view-wrap">
      <el-card shadow="never" v-loading="loading">
        <template #header>
          <div style="font-weight: 700;">用户账号</div>
        </template>

        <el-table :data="users" empty-text="暂无用户">
          <el-table-column prop="username" label="用户名" min-width="180"></el-table-column>
          <el-table-column prop="id" label="用户 ID" min-width="280">
            <template #default="scope">
              <span class="mono-text">{{ scope.row.id }}</span>
            </template>
          </el-table-column>
          <el-table-column label="角色" width="120">
            <template #default="scope">
              <el-tag :type="scope.row.role === 'ADMIN' ? 'danger' : 'info'">
                {{ roleLabel(scope.row.role) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="scope">
              <el-tag :type="scope.row.enabled ? 'success' : 'info'">
                {{ scope.row.enabled ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" min-width="180">
            <template #default="scope">{{ formatTime(scope.row.createdAt) }}</template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <el-dialog v-model="createDialogOpen" title="新增用户" width="460px" destroy-on-close>
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-position="top">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="createForm.username" maxlength="64"
                    placeholder="请输入用户名" autocomplete="off"></el-input>
        </el-form-item>
        <el-form-item label="初始密码" prop="password">
          <el-input v-model="createForm.password" type="password" show-password maxlength="256"
                    placeholder="至少 12 个字符" autocomplete="new-password"></el-input>
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-select v-model="createForm.role" style="width: 100%;" data-test="user-role-select">
            <el-option label="普通用户" value="USER"></el-option>
            <el-option label="管理员" value="ADMIN"></el-option>
          </el-select>
        </el-form-item>
        <div class="muted-text">账号创建后立即启用，密码只会以 BCrypt 哈希保存。</div>
      </el-form>

      <template #footer>
        <el-button @click="createDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="createUser">创建</el-button>
      </template>
    </el-dialog>
  </admin-shell>
</template>

<script>
import { ref, reactive, nextTick } from 'vue';
import { ElMessage } from 'element-plus';

export default {
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
        ElMessage.error('加载用户失败: ' + error.message);
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
        ElMessage.success('用户创建成功');
        await loadUsers();
      } catch (error) {
        ElMessage.error('创建用户失败: ' + error.message);
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
};
</script>