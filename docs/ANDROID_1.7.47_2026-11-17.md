# Android 1.7.47：深浅色过渡同步

versionCode 106。选择深浅色模式时立即更新主题，菜单退场与主题过渡同时进行，不再串行等待。其他菜单保持原有执行时机。

设置标题栏直接使用全局逐帧背景色，内部透明，避免 Material 3 TopAppBar 再对正在变化的颜色执行一次动画。背景、文字、强调色、语义状态色和菜单背景共用一个 320ms 过渡，菜单不再按亮度阈值突然跳色。支持中途反向切换，保留当前页面、滚动位置和输入状态。系统栏继续按当前背景亮度选择图标明暗。

回归包含标题栏/页面像素逐帧对比、途中反向切换、状态色同步、主题选择立即持久化、菜单收起期间禁止重复选择，以及普通菜单退场后仅执行一次。原故障版本在逐帧测试中失败：页面红通道 0.827451 时标题栏仍为 0.956863。

补齐原过渡遗漏的 7 个 Material 3 表面容器色。最终 285 项 JVM 回归与 14 项 Android 模拟器界面回归通过，覆盖新增菜单背景、容器色和此前仅图文保存键灰显的行为。正式 R8 包的 1 项黑盒启动/导航/配置冒烟通过。Lint 为 0 错误、70 项既有警告。首轮系统输入测试遇到模拟器 System UI 无响应弹窗，恢复环境后同一包 13 项通过；最终补充菜单/容器色后的包再次完整通过 14 项，记录保存在 qa。

动效核对使用本地设计知识库资源 `cherry-studio-app.animation.menu-panel`，来源 CherryHQ/cherry-studio-app，固定提交 `e6d157d2ea604ef65d38ca14e88ba92de706ad8a`，AGPL-3.0。仅核对已有菜单生命周期；本次未复制上游或示例代码，主题立即提交是本应用的修复行为，未运行 Cherry 原应用。菜单原有几何与开/关动画保持。Material 3 内部颜色动画参考 [AndroidX AppBar 源码](https://android.googlesource.com/platform/frameworks/support/+/60f47a798ddf39f9b8c7ffba361aeeff4e5287e4/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/AppBar.kt)。

仅交付 Android ARM64；未更新 Windows/iOS、本机 Web 或 GitHub。无实体手机，正式 ARM64 包在模拟器仅进行界面、资源解压和配置检查，未验证实体设备帧率或各厂商系统栏表现。构建、测试、Lint、签名结果见交付目录 qa。
