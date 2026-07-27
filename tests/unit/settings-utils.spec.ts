import { describe, expect, it } from 'vitest';
import * as settings from '../../frontend/js/admin/settings-utils.js';

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
