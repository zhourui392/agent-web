import { describe, expect, it } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const FRONTEND = resolve(__dirname, '../../frontend');

describe('Workflow admin removal', () => {
  it('removes the retired page files and sidebar entry', () => {
    expect(existsSync(resolve(FRONTEND, 'admin/workflows.html'))).toBe(false);
    expect(existsSync(resolve(FRONTEND, 'js/admin/pages/workflows.js'))).toBe(false);
    expect(existsSync(resolve(FRONTEND, 'js/admin/pages/Workflows.vue'))).toBe(false);

    const shell = readFileSync(resolve(FRONTEND, 'js/admin/AdminShell.vue'), 'utf8');
    expect(shell).not.toContain('workflows');
    expect(shell).not.toContain('工作流');
  });
});
