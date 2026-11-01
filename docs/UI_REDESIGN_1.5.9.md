# 1.5.9 Android 界面与交互调整

## 首页标注对应关系

| 图片标注 | 实现 |
| --- | --- |
| 删除顶部平台说明 | 品牌区仅保留图标、应用名和版本号 |
| 输入框改为单行高度 | 单行 TextField，约 56dp；保留分享文本/长链接粘贴与水平滚动 |
| 平台标签移到输入框上方 | 首页顺序变为品牌、平台标签、输入与操作、中转站卡片 |
| 删除第三方中转网站标题/说明/设置链接 | 移除整块标题区；卡片继续直接展示，站点管理放在设置子页 |

## 分类设置

设置首页采用图标、分类标题、摘要和进入箭头的偏好列表，拆成七个子页：

- 外观与语言：语言和主题。
- 下载与存储：保存目录、系统目录选择器与缓存清理。
- 网络代理：填写、应用与清除代理。
- 中转站：添加、删除、管理和完整懒加载站点列表。
- 账号：Bilibili 登录和 Cookie。
- 下载引擎：初始化状态、错误与组件更新；状态只在前台按需刷新。
- 关于：应用版本与支持平台。

顶部返回和系统返回均先返回设置分类页；从其他主标签回到设置时保留当前子页。每个子页有独立保存的滚动状态，代理草稿等输入保留。新增标题、摘要和返回描述覆盖简中、繁中、英语和日语。

## Mihon 参考与具体采用方式

参考仓库为用户链接所指向的 [mihonapp/mihon](https://github.com/mihonapp/mihon)，本次读取 main 对应提交 e588e04359fe1058206a2cbac1b01018ad787359。

1. [分类设置入口](https://github.com/mihonapp/mihon/blob/e588e04359fe1058206a2cbac1b01018ad787359/app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMainScreen.kt)：采用偏好分类列表和进入子页面的组织方式，保留 OmniDownloader 的下载设置功能。
2. [按钮组件](https://github.com/mihonapp/mihon/blob/e588e04359fe1058206a2cbac1b01018ad787359/presentation-core/src/main/java/tachiyomi/presentation/core/components/material/Button.kt)：采用 Material 3 胶囊按钮、原生涟漪与焦点/按下反馈；替换小型手工 Box 操作按钮，图标按钮使用 48dp 触控范围。没有新增夸张缩放或每帧改变布局尺寸的按钮动效。
3. [主标签动效](https://github.com/mihonapp/mihon/blob/e588e04359fe1058206a2cbac1b01018ad787359/app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt)：采用 200ms fade-through 节奏（70ms 淡出、130ms 淡入）。本项目继续使用保留状态的容器，先淡出旧页再切换并淡入新页，同一时刻只放置一个页面；动画值在图层阶段读取。隐藏页继续暂停生命周期感知订阅，淡出时阻止旧页面误触，底栏保持可响应。快速改选会取消并重新定向动画，返回当前页后恢复完整不透明度。
4. [启动实现](https://github.com/mihonapp/mihon/blob/e588e04359fe1058206a2cbac1b01018ad787359/app/src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt)：使用 AndroidX SplashScreen、品牌云下载图标和明暗背景；首次进入保留至少 500ms，避免空白闪烁。Android 12+ 使用系统启动退场；旧系统使用 400ms 淡出和内容 16dp 上移归位。不等待下载引擎解压完成，也不在配置恢复时重复播放完整启动阶段。

同时修正了 ContextWrapper 下查找 Activity 的方式，使主题变化可以设置系统栏图标和手势条的对比度。

## 验证

- 14 项原有 JVM 回归测试通过。
- InterfaceLayoutTest 验证：平台区域在输入框上方、输入框高度不超过 64dp、旧说明与中转站标题已删除；七类子页可进入；系统返回、主题保存、深色系统栏标志、代理草稿和应用保存、主标签返回后的子页状态、中转站第 38 条滚动位置保留，以及切换中文。
- RetainedTabHostTest：隐藏页在高频更新中不持续测量、暂停订阅，保留文本/滚动/remember 实例；快速切页再返回会恢复正确页面和完全不透明的颜色。
- CollectionTaskCardTest：120 集合集仍能访问最后一集。
- AppInteractionTest：700 条历史任务、80 个中转站与 80ms 进度更新下反复切页/滑动通过；本次采样 166 帧，P95 26.35ms，UI 线程 P95 5.57ms，超过 100ms 的帧为 0。该数据来自模拟器合成负载，不代表所有手机上的帧率。
- OptimizedReleaseTest：压缩混淆后的 ARM64 正式包冷启动、历史任务恢复、主标签往返、七个设置子页进入/返回、Python/FFmpeg/yt-dlp 首次解包均通过。此测试未在 x86 模拟器上执行 ARM 原生媒体命令。
- 设备环境：Android 15 / API 35、NVIDIA GLES 硬件加速的专用模拟器。图中的中转站名称为测试数据，不会随安装包创建到用户配置。
- 截图保存于 screenshots/v1.5.9/。启动图由实际冷启动期间采集；界面静态图在涟漪、占位符和系统栏颜色过渡稳定后采集。

正式包已完成 R8/资源裁剪构建、Release Lint 和黑盒启动测试；14 项 JVM 测试、5 项 Debug 设备测试及 1 项正式包黑盒设备测试通过。Android 8–11 的启动退场逻辑已按兼容 API 实现，本次没有对应旧系统模拟器或真机，不宣称完成该分支的设备验证。

## 安装包

- OmniDownloader-v1.5.9-release.apk：55,956,894 字节（53.36 MiB），ARM64，versionCode 50。
- 相比 1.5.8 增加 31,640 字节（约 0.057%），六个原生库载荷 SHA-256 均保持一致。继续启用 R8 与资源裁剪。
- 签名校验通过；OmniDownloader_LATEST.apk 已更新且哈希一致。
- SHA-256：3ABFED821AD4EF5C7513BF05C819CD9AABC9257D0C25410DE986C55CE555070C。
- 根目录保存对应混淆映射 OmniDownloader-v1.5.9-mapping.zip 和 SHA256 清单；结构化结果见 UI_REDESIGN_RESULTS_1.5.9.json。

## 界面预览

- [中文首页](screenshots/v1.5.9/home-zh.png)
- [设置分类](screenshots/v1.5.9/settings-root-zh.png)
- [外观子页与深色模式](screenshots/v1.5.9/settings-appearance-dark.png)
- [实际系统启动图](screenshots/v1.5.9/startup-system.png)

## 构建与设备测试

~~~powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
.\gradlew.bat :app:assembleRelease :app:lintRelease :release-smoke:assembleRelease
~~~

新界面设备测试类：com.omni.downloader.InterfaceLayoutTest。原有保留容器与合集测试类位于 com.omni.downloader.ui.design 包。测试只用于专用测试安装，会写入合成任务/设置与截图。

保持 1.5.8 已启用的代码/资源裁剪和可选 Aria2c 移除；没有引入整套 Mihon 导航依赖或额外图片动画素材。
