<template>
  <admin-shell active="tool-invocations" @ready="loadAll">
    <template #header-actions><el-button text :loading="loading" @click="loadAll">刷新</el-button></template>
    <div v-loading="loading" class="view-wrap">
      <el-row :gutter="16">
        <el-col v-for="item in kpis" :key="item.label" :span="4">
          <el-card class="kpi-card" shadow="never"><div class="kpi-value">{{ item.value }}</div><div class="kpi-label">{{ item.label }}</div></el-card>
        </el-col>
      </el-row>

      <el-card class="mt16" shadow="never">
        <div class="tool-filter-grid">
          <el-select v-model="filters.provider" clearable placeholder="Provider"><el-option label="Claude" value="CLAUDE"></el-option><el-option label="Codex" value="CODEX"></el-option></el-select>
          <el-select v-model="filters.invocationKind" clearable placeholder="调用类别"><el-option label="工具" value="TOOL_USE"></el-option><el-option label="命令执行" value="COMMAND_EXECUTION"></el-option><el-option label="Skill" value="SKILL"></el-option></el-select>
          <el-select v-model="filters.status" clearable placeholder="状态"><el-option v-for="s in statuses" :key="s" :label="statusLabel(s)" :value="s"></el-option></el-select>
          <el-select v-model="filters.triggerSource" clearable placeholder="触发来源"><el-option label="Agent" value="AGENT"></el-option><el-option label="用户 Slash" value="USER_SLASH"></el-option></el-select>
          <el-input v-model="filters.toolName" clearable placeholder="工具名"></el-input>
          <el-input v-model="filters.skillName" clearable placeholder="Skill 名称"></el-input>
          <el-input v-model="filters.sessionId" clearable placeholder="Session ID"></el-input>
          <el-input v-model="filters.runId" clearable placeholder="Run ID"></el-input>
          <el-date-picker v-model="dateRange" type="datetimerange" range-separator="至" start-placeholder="开始时间" end-placeholder="结束时间" value-format="x"></el-date-picker>
          <div><el-button type="primary" @click="search">查询</el-button><el-button @click="reset">重置</el-button></div>
        </div>
      </el-card>

      <el-card class="mt16" shadow="never">
        <el-table :data="rows" border size="small" empty-text="暂无工具调用">
          <el-table-column label="时间" width="170"><template #default="{ row }">{{ time(row.startedAt) }}</template></el-table-column>
          <el-table-column label="Provider" width="90"><template #default="{ row }"><el-tag size="small">{{ row.provider }}</el-tag></template></el-table-column>
          <el-table-column label="类别" width="110"><template #default="{ row }">{{ kindLabel(row.invocationKind) }}</template></el-table-column>
          <el-table-column label="工具 / Skill" min-width="150"><template #default="{ row }"><b>{{ row.skillName || row.toolName || '命令执行' }}</b><div class="muted">{{ row.triggerSource }}</div></template></el-table-column>
          <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag></template></el-table-column>
          <el-table-column label="输入摘要" min-width="250" show-overflow-tooltip prop="inputSummary"></el-table-column>
          <el-table-column label="Session / Run" min-width="190"><template #default="{ row }"><div>{{ row.sessionId }}</div><div class="muted">{{ row.runId || '-' }}</div></template></el-table-column>
          <el-table-column label="操作" width="80" fixed="right"><template #default="{ row }"><el-button text type="primary" size="small" @click="openDetail(row.id)">详情</el-button></template></el-table-column>
        </el-table>
        <div class="conv-pager"><el-pagination background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :current-page="page" :page-sizes="[10,20,50,100]" @current-change="changePage" @size-change="changeSize"></el-pagination></div>
      </el-card>
    </div>

    <el-drawer v-model="detailOpen" title="工具调用详情" size="58%">
      <div v-loading="detailLoading" v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="ID">{{ detail.id }}</el-descriptions-item><el-descriptions-item label="Provider">{{ detail.provider }}</el-descriptions-item>
          <el-descriptions-item label="类别">{{ kindLabel(detail.invocationKind) }}</el-descriptions-item><el-descriptions-item label="状态">{{ statusLabel(detail.status) }}</el-descriptions-item>
          <el-descriptions-item label="工具">{{ detail.toolName || '-' }}</el-descriptions-item><el-descriptions-item label="Skill">{{ detail.skillName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="Session">{{ detail.sessionId }}</el-descriptions-item><el-descriptions-item label="Run">{{ detail.runId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="Provider Call">{{ detail.providerCallId || '-' }}</el-descriptions-item><el-descriptions-item label="Item Type">{{ detail.providerItemType || '-' }}</el-descriptions-item>
          <el-descriptions-item label="Exit Code">{{ detail.exitCode == null ? '-' : detail.exitCode }}</el-descriptions-item><el-descriptions-item label="Provider Status">{{ detail.providerStatus || '-' }}</el-descriptions-item>
          <el-descriptions-item label="输入截断">{{ detail.inputTruncated ? '是' : '否' }}</el-descriptions-item><el-descriptions-item label="输出截断">{{ detail.outputTruncated ? '是' : '否' }}</el-descriptions-item>
        </el-descriptions>
        <div class="section-title mt16">输入</div><pre class="tool-detail-pre">{{ pretty(detail.inputJson) }}</pre>
        <div class="section-title mt16">输出</div><pre class="tool-detail-pre">{{ detail.outputText || '-' }}</pre>
      </div>
    </el-drawer>
  </admin-shell>
</template>

<script setup>
import { computed, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import AdminShell from '../AdminShell.vue';
import { fetchJson, withLoading } from '../../lib/admin-fetch.ts';

const loading=ref(false), detailLoading=ref(false), rows=ref([]), total=ref(0), page=ref(1), size=ref(20), overview=ref({}), dateRange=ref(null), detailOpen=ref(false), detail=ref(null);
const statuses=['STARTED','SUCCEEDED','FAILED','INCOMPLETE','UNKNOWN'];
const filters=reactive({provider:'',invocationKind:'',status:'',triggerSource:'',toolName:'',skillName:'',sessionId:'',runId:''});
const kpis=computed(()=>[
  {label:'总调用',value:overview.value.total||0},{label:'Claude',value:overview.value.claude||0},{label:'Codex',value:overview.value.codex||0},
  {label:'命令执行',value:overview.value.commandExecutions||0},{label:'Skill',value:overview.value.skills||0},{label:'失败 / 不完整',value:(overview.value.failed||0)+' / '+(overview.value.incomplete||0)}]);
function params(){const p=new URLSearchParams({page:String(page.value),size:String(size.value)});Object.entries(filters).forEach(([k,v])=>{if(v)p.set(k,v)});if(dateRange.value){p.set('startedAfter',dateRange.value[0]);p.set('startedBefore',dateRange.value[1])}return p;}
async function loadRows(){const data=await fetchJson('/api/admin-tool-invocations?'+params());rows.value=data.items||[];total.value=data.total||0;}
async function loadAll(){try{await withLoading(loading,async()=>{const data=await Promise.all([fetchJson('/api/admin-tool-invocations/overview'),loadRows()]);overview.value=data[0]||{};});}catch(e){ElMessage.error(e.message)}}
function search(){page.value=1;loadRows().catch(e=>ElMessage.error(e.message));}
function reset(){Object.keys(filters).forEach(k=>filters[k]='');dateRange.value=null;search();}
function changePage(v){page.value=v;loadRows();} function changeSize(v){size.value=v;page.value=1;loadRows();}
async function openDetail(id){detailOpen.value=true;detail.value=null;try{await withLoading(detailLoading,async()=>{detail.value=await fetchJson('/api/admin-tool-invocations/'+id);});}catch(e){ElMessage.error(e.message)}}
function time(v){return v?new Date(v).toLocaleString('zh-CN'):'-';} function kindLabel(v){return {TOOL_USE:'工具',COMMAND_EXECUTION:'命令执行',SKILL:'Skill'}[v]||v;}
function statusLabel(v){return {STARTED:'执行中',SUCCEEDED:'成功',FAILED:'失败',INCOMPLETE:'不完整',UNKNOWN:'未知'}[v]||v;} function statusType(v){return v==='SUCCEEDED'?'success':v==='FAILED'?'danger':v==='INCOMPLETE'?'warning':'info';}
function pretty(v){if(!v)return '-';try{return JSON.stringify(JSON.parse(v),null,2)}catch(e){return v;}}
</script>
