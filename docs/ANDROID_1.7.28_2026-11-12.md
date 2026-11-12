# Android 1.7.28

仅 Android，versionCode 87。中型 Button、OutlinedButton 和 FilledTonalButton 使用 MaterialTheme.shapes.medium（14dp），覆盖首页、批量、图文、任务、设置与相关对话框。已有明确形状以及明确高度不小于 52dp 的大型操作按钮保持原形状；图标按钮、筛选标签和卡片不改形状。

底部导航继续使用 Material 3 NavigationBarItem，保留选中背景的展开、涟漪、标签和现有页面淡入淡出。新增线框到实心图标自下而上的 200ms 填充，取消选中时反向收回。仅对图标绘制区域裁切，不修改触摸区域和无障碍名称，不触发页面列表测量；快速切换可从当前动画值转向最新目标，不积累延时任务。该图标过渡是本项目对参考风格的适配，并非声称与 Mihon 逐帧相同。

参考：[Mihon HomeScreen 的 Material 导航结构](https://github.com/mihonapp/mihon/blob/main/app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt)。

Android 15 x86_64 模拟器使用可控动画时钟，比较导航图标开始、中间、结束帧的实际渲染差异，检查连续快速切页后的唯一选中状态。Release 构建、Lint、优化包导航与设置检查的结果见 qa 日志。原 ARM64 载荷和签名保持；未完成 ARM64 真机验收。本轮未发布 GitHub Release。
