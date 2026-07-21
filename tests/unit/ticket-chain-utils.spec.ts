import { describe, it, expect } from 'vitest';
import { createRequire } from 'node:module';

const requireCjs = createRequire(import.meta.url);
const utils = requireCjs('../../src/main/resources/static/js/admin/ticket-chain-utils.js') as {
  stageLabel: (stage: string | null | undefined) => string;
  funnelLabel: (step: string | null | undefined) => string;
  pct: (value: number | null | undefined) => string;
  deltaPct: (value: number | null | undefined) => string;
  num: (value: number | null | undefined) => string;
  duration: (value: number | null | undefined) => string;
  stageP50: (overview: unknown, stageName: string) => number | null;
  buildKpis: (overview: unknown) => Array<{ label: string; value: string }>;
  mapEntries: (obj: unknown) => Array<{ key: string; count: unknown }>;
};

describe('stageLabel / funnelLabel', () => {
  it('maps known enums to Chinese', () => {
    expect(utils.stageLabel('E2E')).toBe('端到端');
    expect(utils.stageLabel('ACCEPT')).toBe('受理');
    expect(utils.stageLabel('DIAGNOSING')).toBe('诊断中');
    expect(utils.stageLabel('SUBMIT_DEAD')).toBe('投递死信');
    expect(utils.funnelLabel('INBOUND')).toBe('入站');
    expect(utils.funnelLabel('POSITIVE_CLOSED')).toBe('正向闭环');
  });

  it('falls back to raw value for unknown enums', () => {
    expect(utils.stageLabel('FUTURE_STAGE')).toBe('FUTURE_STAGE');
    expect(utils.funnelLabel('FUTURE_STEP')).toBe('FUTURE_STEP');
  });

  it('uses placeholder for null/undefined', () => {
    expect(utils.stageLabel(null)).toBe('-');
    expect(utils.funnelLabel(undefined)).toBe('-');
  });
});

describe('pct / num / duration placeholders', () => {
  it('pct formats ratio else placeholder', () => {
    expect(utils.pct(0.1234)).toBe('12.3%');
    expect(utils.pct(0)).toBe('0.0%');
    expect(utils.pct(null)).toBe('-');
    expect(utils.pct(undefined)).toBe('-');
  });

  it('deltaPct formats signed percentage points else placeholder', () => {
    expect(utils.deltaPct(0.123)).toBe('+12.3pp');
    expect(utils.deltaPct(-0.05)).toBe('-5.0pp');
    expect(utils.deltaPct(0)).toBe('0.0pp');
    expect(utils.deltaPct(null)).toBe('-');
  });

  it('num stringifies counts else placeholder', () => {
    expect(utils.num(0)).toBe('0');
    expect(utils.num(42)).toBe('42');
    expect(utils.num(null)).toBe('-');
  });

  it('duration scales ms / s / m and rejects negatives', () => {
    expect(utils.duration(880)).toBe('880ms');
    expect(utils.duration(1500)).toBe('1.5s');
    expect(utils.duration(90000)).toBe('1.5m');
    expect(utils.duration(-1)).toBe('-');
    expect(utils.duration(null)).toBe('-');
  });
});

describe('stageP50', () => {
  const overview = { stage: [{ stage: 'E2E', p50Ms: 12000 }, { stage: 'ACCEPT', p50Ms: 300 }] };

  it('finds the matching stage p50', () => {
    expect(utils.stageP50(overview, 'E2E')).toBe(12000);
    expect(utils.stageP50(overview, 'ACCEPT')).toBe(300);
  });

  it('returns null when missing or empty', () => {
    expect(utils.stageP50(overview, 'REPLY')).toBeNull();
    expect(utils.stageP50(null, 'E2E')).toBeNull();
    expect(utils.stageP50({}, 'E2E')).toBeNull();
  });
});

describe('buildKpis', () => {
  it('lands placeholders when overview is empty (null rates)', () => {
    const kpis = utils.buildKpis(null);
    expect(kpis).toHaveLength(9);
    expect(kpis[0]).toEqual({ label: '端到端 p50', value: '-' });
    expect(kpis.every(k => k.value === '-')).toBe(true);
  });

  it('formats values when present', () => {
    const overview = {
      stage: [{ stage: 'E2E', p50Ms: 12000 }],
      inbound: { gateRejectRate: 0.05 },
      submit: { firstTryOk: 18, deadRate: 0.02 },
      reply: { deliverRate: 0.97, droppedSilently: 1 },
      diagnose: { successRate: 0.9 },
      feedback: { coverageRate: 0.6, oneShotRate: 0.8 }
    };
    const kpis = utils.buildKpis(overview);
    const byLabel = Object.fromEntries(kpis.map(k => [k.label, k.value]));
    expect(byLabel['端到端 p50']).toBe('12.0s');
    expect(byLabel['门禁误杀率']).toBe('5.0%');
    expect(byLabel['首投成功']).toBe('18');
    expect(byLabel['投递死信率']).toBe('2.0%');
    expect(byLabel['结论送达率']).toBe('97.0%');
    expect(byLabel['静默跳过']).toBe('1');
    expect(byLabel['诊断成功率']).toBe('90.0%');
    expect(byLabel['反馈覆盖率']).toBe('60.0%');
    expect(byLabel['一次解决率']).toBe('80.0%');
  });
});

describe('mapEntries', () => {
  it('expands a map into key/count rows', () => {
    expect(utils.mapEntries({ 飞书: 3, API: 5 })).toEqual([
      { key: '飞书', count: 3 },
      { key: 'API', count: 5 }
    ]);
  });

  it('returns empty array for null / non-object', () => {
    expect(utils.mapEntries(null)).toEqual([]);
    expect(utils.mapEntries(undefined)).toEqual([]);
    expect(utils.mapEntries('x')).toEqual([]);
  });
});
