/**
 * Element Plus 按需注册。替代 app.use(ElementPlus) 全量注册。
 *
 * 为什么手工列而不用 unplugin-vue-components:
 * 那个插件靠构建期扫 .vue / .jsx 模板 AST 推断用到了哪些组件, 而本项目 0 个 .vue --
 * 49 种 el-* 全写在 HTML 的 in-DOM 模板里(index.html 131 处),
 * 由浏览器运行时编译, 构建期看不见这些标签。所以只能显式枚举。
 * 等 FE-R3 迁 SFC 后可换回 unplugin 自动推断。
 *
 * 维护约定: 模板里新用一个 el-xxx, 必须在 COMPONENTS 里补上对应导出, 否则该组件
 * 在运行时解析不到 -- 表现为标签原样留在 DOM 里不渲染, 且只在用到它的那个页面暴露。
 * 校验办法: grep -rhoE "<el-[a-z-]+" src/main/frontend/ | sort -u 对齐本清单。
 *
 * @author zhourui(V33215020)
 */
import {
  ElAlert, ElAside, ElBadge, ElButton, ElCard, ElCheckbox,
  ElCheckboxGroup, ElCol, ElCollapse, ElCollapseItem,
  ElContainer, ElDatePicker, ElDescriptions,
  ElDescriptionsItem, ElDialog, ElDivider, ElDrawer, ElDropdown, ElDropdownItem,
  ElDropdownMenu, ElEmpty, ElForm, ElFormItem, ElHeader, ElIcon, ElImage, ElInput,
  ElInputNumber, ElMain, ElMenu, ElMenuItem, ElOption, ElPagination, ElPopover,
  ElRadioButton, ElRadioGroup, ElResult, ElRow, ElScrollbar, ElSelect, ElSwitch, ElTabPane,
  ElTable, ElTableColumn, ElTabs, ElTag, ElTooltip,
  ElUpload, ElLoading,
} from 'element-plus';
import * as ElementPlusIconsVue from '@element-plus/icons-vue';

const COMPONENTS = [
  ElAlert, ElAside, ElBadge, ElButton, ElCard, ElCheckbox,
  ElCheckboxGroup, ElCol, ElCollapse, ElCollapseItem,
  ElContainer, ElDatePicker, ElDescriptions,
  ElDescriptionsItem, ElDialog, ElDivider, ElDrawer, ElDropdown, ElDropdownItem,
  ElDropdownMenu, ElEmpty, ElForm, ElFormItem, ElHeader, ElIcon, ElImage, ElInput,
  ElInputNumber, ElMain, ElMenu, ElMenuItem, ElOption, ElPagination, ElPopover,
  ElRadioButton, ElRadioGroup, ElResult, ElRow, ElScrollbar, ElSelect, ElSwitch, ElTabPane,
  ElTable, ElTableColumn, ElTabs, ElTag, ElTooltip,
  ElUpload,
];

export function setupElementPlus(app) {
  for (const comp of COMPONENTS) {
    // Element Plus 组件自带 name ("ElButton"), app.component 会按它注册;
    // 运行时模板里的 kebab-case <el-button> 由 Vue 自行做 kebab -> Pascal 匹配。
    app.component(comp.name, comp);
  }

  // v-loading 是指令不是组件(模板里 19 处), 全量 use 时随包注册, 按需注册必须显式补。
  app.directive('loading', ElLoading.directive);

  // 图标保持全量注册: index.html 有 <component :is="t.enabled ? 'video-pause' : ..."/>
  // 按字符串名动态解析, 构建期无法枚举, 少注册一个就会在运行时空渲染。
  // 同时注册 PascalCase / 全小写 / kebab-case 三种键, 兼容模板里的各种写法。
  for (const [name, comp] of Object.entries(ElementPlusIconsVue)) {
    app.component(name, comp);
    app.component(name.toLowerCase(), comp);
    const kebab = name.replace(/([a-z])([A-Z])/g, '$1-$2').toLowerCase();
    if (kebab !== name.toLowerCase()) {
      app.component(kebab, comp);
    }
  }
}
