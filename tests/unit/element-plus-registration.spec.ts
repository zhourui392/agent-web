import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';

/**
 * 守卫: Element Plus 改成按需注册后, 模板里用到的每个 el-* 都必须在
 * js/element-plus-setup.js 的 COMPONENTS 里登记。
 *
 * 漏注册的失败模式很隐蔽: 组件解析不到时 Vue 不抛异常, 标签原样留在 DOM 里不渲染,
 * 且只在用到它的那个页面暴露 -- e2e 未必覆盖到每个页面的每个分支。故用静态断言兜住。
 */
const FRONTEND = resolve(__dirname, '../../src/main/frontend');

function walk(dir: string, exts: string[], out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) {
      walk(p, exts, out);
    } else if (exts.some((e) => name.endsWith(e))) {
      out.push(p);
    }
  }
  return out;
}

/** kebab -> Pascal: el-date-picker -> ElDatePicker */
function toPascal(kebab: string): string {
  return 'El' + kebab.replace(/^el-/, '').split('-')
    .map((s) => s.charAt(0).toUpperCase() + s.slice(1)).join('');
}

describe('Element Plus 按需注册完整性', () => {
  // 模板散落在 HTML (in-DOM 模板)、JS (字符串模板) 和 .vue (SFC 预编译模板) 三处, 都要扫
  const files = walk(FRONTEND, ['.html', '.js', '.vue']);
  const used = new Set<string>();
  for (const f of files) {
    if (f.endsWith('element-plus-setup.js')) continue;
    const src = readFileSync(f, 'utf8');
    for (const m of src.matchAll(/<(el-[a-z0-9-]+)/g)) {
      used.add(m[1]);
    }
  }

  const setupSrc = readFileSync(join(FRONTEND, 'js/element-plus-setup.js'), 'utf8');
  // 取 COMPONENTS 数组字面量里的标识符
  const block = setupSrc.match(/const COMPONENTS = \[([\s\S]*?)\];/);
  const registered = new Set(
    (block?.[1] ?? '').split(',').map((s) => s.trim()).filter(Boolean)
  );

  it('模板里出现的 el-* 组件全部已注册', () => {
    expect(used.size).toBeGreaterThan(30); // 扫描确实生效, 不是空集导致假绿
    const missing = [...used].filter((k) => !registered.has(toPascal(k))).sort();
    expect(missing, `以下组件在模板中使用但未注册:\n  ${missing.join('\n  ')}`).toEqual([]);
  });

  it('注册清单里没有模板不再使用的组件 (避免白留体积)', () => {
    const usedPascal = new Set([...used].map(toPascal));
    const unused = [...registered].filter((c) => !usedPascal.has(c)).sort();
    expect(unused, `以下组件已注册但模板中未使用:\n  ${unused.join('\n  ')}`).toEqual([]);
  });

  it('v-loading 指令有显式注册 (按需注册下不会随包带上)', () => {
    const usesLoading = files.some((f) => !f.endsWith('element-plus-setup.js')
      && /v-loading/.test(readFileSync(f, 'utf8')));
    if (usesLoading) {
      expect(setupSrc).toMatch(/app\.directive\(\s*'loading'\s*,\s*ElLoading\.directive\s*\)/);
    }
  });
});
