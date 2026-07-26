<template>
  <div v-if="recall" class="recall-card">
    <div class="recall-card-head" @click="recall.recallOpen = !recall.recallOpen">
      <span class="recall-card-toggle" :class="{expanded: recall.recallOpen}">▶</span>
      <span class="recall-card-title">🔍 召回了 {{ recall.hits.length }} 条历史参考</span>
      <span v-if="recall.query" class="recall-card-query">"{{ recall.query }}"</span>
    </div>
    <div v-show="recall.recallOpen" class="recall-card-body">
      <div v-for="(h, hi) in recall.hits" :key="hi" class="recall-hit">
        <div class="recall-hit-title">{{ hi + 1 }}. {{ h.title }}</div>
        <div v-if="h.conclusion" class="recall-hit-conclusion">{{ h.conclusion }}</div>
      </div>
      <div v-if="!recall.hits.length" class="recall-empty">无匹配历史，已照常发送原消息</div>
    </div>
  </div>
</template>

<script setup lang="ts">
interface RecallHit {
  title: string;
  conclusion?: string;
}

interface RecallData {
  hits: RecallHit[];
  query?: string;
  recallOpen: boolean;
}

defineProps<{ recall: RecallData | null }>();
</script>