# Android 1.7.33：Cherry Studio Mobile 风格选项菜单

versionCode 92。仅调整 Android 的按钮弹出选项菜单，保留应用配色、按钮外形和各选项原有功能。

## 参考与实现

根据 CherryHQ/cherry-studio-app 的 Android ActionMenu 源码实现 Compose 组件，没有引入 React Native 或新增运行依赖。参考为 2026-11-13 读取的 main 分支：

- [ActionMenu Android](https://github.com/CherryHQ/cherry-studio-app/blob/main/packages/ui/src/components/menu/action-menu/action-menu.android.tsx)：按钮菜单使用共享的锚定面板。
- [MenuContent](https://github.com/CherryHQ/cherry-studio-app/blob/main/packages/ui/src/components/menu/menu-content.tsx) 与 [MenuPanel](https://github.com/CherryHQ/cherry-studio-app/blob/main/packages/ui/src/components/menu/menu-panel.tsx)：从 32dp 面板展开至 208dp，内容短距离滑入，附带淡入与轻微缩放；长菜单可滚动，默认高度上限 480dp。
- [menu-motion](https://github.com/CherryHQ/cherry-studio-app/blob/main/packages/ui/src/components/menu/menu-motion.ts) 与 [motion](https://github.com/CherryHQ/cherry-studio-app/blob/main/packages/ui/src/motion.ts)：打开 350ms，曲线 (0.34, 1.25, 0.64, 1)，轻微回弹；收起 250ms，曲线 (0.22, 1, 0.36, 1)。内容从右侧 40dp 位移、0.97 倍缩放过渡至稳定状态。
- [MenuSurface](https://github.com/CherryHQ/cherry-studio-app/blob/main/packages/ui/src/components/menu/menu-surface.tsx)、[MenuRow](https://github.com/CherryHQ/cherry-studio-app/blob/main/packages/ui/src/components/menu/menu-row.tsx) 与 [native.css](https://github.com/CherryHQ/cherry-studio-app/blob/main/packages/design-tokens/src/styles/native.css)：不透明圆角表面、柔和阴影，面板圆角 20.8dp、选项圆角 14.4dp，8dp 内边距、2dp 行间距、选项最小高度 56dp；选中勾位于右侧。

共用组件 `OmniOptionMenu` / `OmniOptionMenuItem` 应用于：设置页的主题、语言和下载选项，批量页的下载类型和音频格式，以及图文输入组件的保存路径菜单。常规确认对话框、下载详情底部弹层保留原有行为。

浅色使用白色面板，深色使用应用的中性表面色；文字保留现有字体与字号体系。按压高亮、禁用状态、单选语义、长文字换行和滚动均集中处理。菜单在按钮下方空间不足时向上展开，保留屏幕边缘间距；窗口尺寸、字体配置或键盘尺寸变化时关闭原菜单。

选择选项后立即禁止重复点击，保留面板至收起动画结束，再执行一次操作。返回键或点菜单外只关闭菜单。中途重新展开从当前动画进度继续，旧的关闭回调不会遗留。动画使用 Compose 的系统时长缩放，关闭系统动画时不强制播放固定时长动画。

Android 12 / API 31 及以上使用随进度减弱的 2dp 内容模糊；Android 8–11 保留相同展开、回弹、滑入和淡入，省略不受支持的 RenderEffect 模糊。未提高最低 Android 版本。

## 验证

- JVM：231 项通过，包括新增的菜单锚定、底部向上展开、左右边缘与小窗口位置测试。
- Android 15 x86_64 专用模拟器：5 项菜单设备测试通过，覆盖选中/禁用状态、退出后只回调一次、返回键、菜单外点击、中途关闭再打开、长菜单滚动至最后一项；另 1 项真实设置页测试验证深浅色与语言选项持久化。
- 原有批量剪贴板和批量工作区 2 项集成测试通过，覆盖实际下载类型/音频格式菜单、草稿与横竖屏恢复。系统 animator_duration_scale 设为 0 时，批量工作区选择回归通过；测试结束恢复原系统配置。
- 设备截图检查实际设置页和独立菜单的浅色、深色及展开中状态。最初手动时钟测试未等待原生 Popup 的布局提交，且滚动动画未推进；补足帧与窗口同步后通过。菜单外点击测试改为系统层触摸注入，使事件经过独立 Popup 窗口；初始日志保留。
- ARM64 R8 Release 包启动、导航、设置子页与批量页冒烟通过；模拟器跳过 ARM64 原生代码执行，未进行实体手机验证。
- Release 构建通过，Lint：0 errors、51 warnings。签名与 1.7.32 一致；10 个原生/共享解析资源条目字节一致。

本轮未修改 Windows/iOS，也未发布至 GitHub。安装包、源码、截图、原始日志与 SHA-256 校验文件保存在本地交付目录。
