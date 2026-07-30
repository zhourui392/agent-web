import { describe, expect, it } from 'vitest';
import { formatStatisticsPercentage, statisticsDateRange, statisticsQualityText } from '../../frontend/js/admin/tool-invocation-statistics';

describe('tool invocation statistics utilities', () => {
  it('formats rates and empty values', () => {
    expect(formatStatisticsPercentage(0.9561)).toBe('95.61%');
    expect(formatStatisticsPercentage(null)).toBe('—');
  });

  it('builds a natural-day left-closed right-open range', () => {
    const value = statisticsDateRange(7, new Date(2026, 6, 29, 10));
    expect(new Date(value.startedAfter).getDate()).toBe(23);
    expect(new Date(value.startedBefore).getDate()).toBe(30);
    expect(value.startedBefore - value.startedAfter).toBe(7 * 86_400_000);
  });

  it('explains migrated and live records', () => {
    expect(statisticsQualityText(32757, 0)).toContain('历史迁移 32,757 条');
    expect(statisticsQualityText(32757, 0)).toContain('不包含真实耗时');
  });
});
