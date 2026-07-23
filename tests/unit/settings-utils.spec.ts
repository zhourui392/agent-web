import { createRequire } from 'node:module';
import { describe, expect, it } from 'vitest';

const requireCjs = createRequire(import.meta.url);
const settings = requireCjs('../../src/main/resources/static/js/admin/settings-utils.js') as {
  pathsToText: (paths: string[] | null | undefined) => string;
  textToPaths: (text: string | null | undefined) => string[];
};

describe('Admin workspace settings path conversion', () => {
  it('renders persisted paths as one path per line', () => {
    expect(settings.pathsToText(['/srv/workspace', '/srv/project']))
      .toBe('/srv/workspace\n/srv/project');
    expect(settings.pathsToText(undefined)).toBe('');
  });

  it('trims lines and removes blank lines before submission', () => {
    expect(settings.textToPaths(' /srv/workspace \r\n\n  /srv/project\n'))
      .toEqual(['/srv/workspace', '/srv/project']);
    expect(settings.textToPaths(null)).toEqual([]);
  });
});
