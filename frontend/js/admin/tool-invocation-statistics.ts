export function formatStatisticsPercentage(value: number | null | undefined): string {
  return value == null ? '—' : (value * 100).toFixed(2) + '%';
}

export function statisticsQualityText(historyCount: number, liveCount: number): string {
  return `历史迁移 ${Number(historyCount || 0).toLocaleString('zh-CN')} 条 · 实时 ${Number(liveCount || 0).toLocaleString('zh-CN')} 条。历史记录不包含真实耗时和 Run 关联。`;
}

export function statisticsDateRange(days: number, now: Date = new Date()): { startedAfter: number; startedBefore: number } {
  const end = new Date(now);
  end.setHours(24, 0, 0, 0);
  const start = new Date(end);
  start.setDate(start.getDate() - days);
  return { startedAfter: start.getTime(), startedBefore: end.getTime() };
}
