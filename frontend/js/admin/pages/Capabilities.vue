<template>
  <admin-shell active="capabilities" @ready="ready = true">
    <div class="view-wrap admin-capability-view">
      <div class="page-heading">
        <div>
          <h2>Workbench 动态阶段</h2>
          <p>先配置可信能力来源，再为 Stage 创建、发布或停用不可变配置版本。</p>
        </div>
      </div>

      <el-tabs v-if="ready" v-model="activeSection" type="border-card">
        <el-tab-pane label="能力来源" name="sources">
          <capability-source-settings
            @catalog-changed="capabilityCatalog = $event"
          />
        </el-tab-pane>
        <el-tab-pane label="Stage Catalog" name="stages">
          <stage-catalog-settings :capability-catalog="capabilityCatalog" />
        </el-tab-pane>
      </el-tabs>
    </div>
  </admin-shell>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import AdminShell from '../AdminShell.vue';
import CapabilitySourceSettings from '../components/CapabilitySourceSettings.vue';
import StageCatalogSettings from '../components/StageCatalogSettings.vue';
import type { CapabilitySourceValidationResult } from '../api/capability-sources.js';

const ready = ref(false);
const activeSection = ref<'sources' | 'stages'>('sources');
const capabilityCatalog = ref<CapabilitySourceValidationResult | null>(null);
</script>

<style scoped>
.admin-capability-view { max-width: 1280px; }
.page-heading { margin-bottom: 14px; }
.page-heading h2 { margin: 0 0 6px; }
.page-heading p { margin: 0; color: var(--el-text-color-secondary); }
</style>
