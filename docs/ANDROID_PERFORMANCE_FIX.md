# Android 切页与滑动性能修复（1.5.7）

## 范围与基线

针对 Android 主页面、任务子标签、任务卡片和中转站设置列表。基于工作目录已有修改继续修复，保留 iOS 和 Windows 的现有改动。

## 原因与实施方案

| 原因 | 修复 |
| --- | --- |
| 主页面只改变全屏 alpha，隐藏页面仍在布局树中；定时预组合与交互争用主线程 | 统一 RetainedTabHost，保存已访问页面的组合，只测量/放置当前页面；缓存页面回调与测量策略，切页时清理输入焦点 |
| 主/子标签整屏转场同时运行两个复杂列表，并添加全屏图层 | 主/子标签即时切换，保留底栏/分段按钮的选中反馈；去掉列表位置动画 |
| collectAsState 在隐藏页面和应用后台继续收集 | 每页独立生命周期，隐藏时上限 CREATED，嵌套页面继承父页上限；界面统一 collectAsStateWithLifecycle |
| 加载转圈动画与界面弹窗在隐藏页仍然运行 | 加载动画离开隐藏页组合；首页结果/错误/登录弹窗只在当前页展示；已授权的中转解析继续执行 |
| 每次下载回调复制并遍历整张任务表，限频未广播的修改被下一次旧快照覆盖 | ID 索引保存真实最新任务，常规进度 O(1) 替换单条，单任务 StateFlow 更新；兼容全量快照合并到 250ms，结构流仅在结构变化时更新 |
| 同步启动读盘；序列化持有界面读写锁；并发保存可能覆盖新数据 | IO 协程恢复任务并重放恢复期间的操作；单写者合并请求，锁外序列化，临时文件原子替换；文件删除放 IO 协程 |
| 任务分组、分集排序、筛选和计数在 Compose 执行 | 结构快照在 Dispatchers.Default 提前投影，三个任务子标签共享结果，减少首次切入的空列表过渡 |
| 合集进度每个回调扫描全部分集 | 缓存固定进度部分，更新当前分集时 O(1) 计算总进度 |
| 设置页 Column 一次性创建全部站点且限制 300dp；分集明细一次创建多条且截断其余条目 | 站点平铺进主 LazyColumn；合集采用有界 LazyColumn，所有分集可访问，按 ID 和 contentType 复用 |
| 删除合集逐条重建列表/保存 | 一次批量删除，一次结构发布；序列化和文件删除均不在主线程 |
| UI_HIDDEN 被当作低内存，切后台就清空图片缓存 | 仅在明确低内存/后台回收等级清理 |

## 验证

### JVM 与静态检查

- 14 项单元测试：6 项已有下载媒体源测试、6 项仓库测试、2 项任务投影测试。
- 仓库覆盖 10000 条任务、1000 轮两任务交错进度更新，结构快照保持不变，最新数值不丢失；已有进度观察者不被新增任务替换。
- 覆盖异步恢复期间新增/删除/清除、未完成任务恢复为已取消、连续终态变更最终落盘、异步删除文件。
- Debug/Release 与设备测试 APK 构建；Android Lint（零 error，项目存在 warning）。

当前 Windows 中文路径会导致 Gradle 8.5/JDK 17 测试工作进程 ClassNotFoundException（包含修改前已有测试）。本次用临时 ASCII 盘符映射到同一工作目录运行，结束后移除映射，没有复制或重置源码。重跑时可在 ASCII 路径运行 Gradle。

### 设备回归

- Android 15 / API 35 x86_64 模拟器（支持 arm64 翻译）；最终使用 NVIDIA RTX 4060 Ti 的 GLES 硬件加速。软件渲染仅作为前期诊断。
- 页面保留：输入文本、列表第 100 行与 remember 实例在来回切页后保留；隐藏页无可见节点，不持续测量；只保留当前页的流订阅。
- 大合集：120 集列表可滚动访问到最后一集。
- 完整应用交互：700 条任务、80 个本地模拟站点、本地封面及每 80ms 更新的模拟任务进度；预热各页后重复 12 次主页面切换和 24 次滑动，无公共网络下载。

### 对照结果与边界

相同可见内容对照使用 700 条历史任务、3 个站点、持续模拟进度，每次点击/滑动后额外停顿 150ms，避免无动画版本因测试脚本更快而连续挤压渲染队列。各次测试均在构建结束后串行执行。

| 指标 | 1.5.6 Release | 1.5.7 Release |
| --- | ---: | ---: |
| UI 主线程 P95（ms） | 7.301 | 5.998 |
| 总帧 P95（ms） | 29.145 | 27.431 |
| >32ms 帧数 | 20 / 454 | 14 / 446 |
| >100ms 帧数 | 0 / 454 | 3 / 446 |

这一次成对对照的 UI 主线程 P95 降低约 18%；总帧长尾仍有波动，不能据此宣称已消除全部长帧。

80 站点的无额外停顿压力测试完成了两轮前后版本交替运行，均无崩溃或交互测试失败。旧版会把站点压进 300dp 并截断，修复版可以滚动到全部站点，两版的可见工作量并不相同，因此这组只作为压力验证：

| 版本/轮次 | UI P95（ms） | 总帧 P95（ms） | >100ms 帧数 |
| --- | ---: | ---: | ---: |
| 1.5.6 / 1 | 5.083 | 19.456 | 1 / 176 |
| 1.5.7 / 1 | 7.258 | 86.126 | 5 / 139 |
| 1.5.6 / 2 | 5.545 | 25.930 | 1 / 175 |
| 1.5.7 / 2 | 7.340 | 50.779 | 3 / 135 |

原始数据见 [ANDROID_PERFORMANCE_RESULTS.json](ANDROID_PERFORMANCE_RESULTS.json)，同时保留了软件渲染下波动较大的前期结果。FrameMetrics 的 UI 工作耗时包含输入、动画、测量布局和绘制录制；TOTAL_DURATION 还受 GPU、同步、模拟器和宿主负载影响。

本次确认了进度不丢失、隐藏页订阅/持续测量隔离、页面状态保留、完整分集可达性，以及合成场景下的界面运行。尚未连接真机，也未执行登录后的真实平台下载回归；不能承诺任何设备上完全零卡顿。

## 交付

- OmniDownloader-v1.5.7-release.apk（versionCode 48）
- OmniDownloader-v1.5.7-debug.apk
- OmniDownloader_LATEST.apk 指向本次 Release 内容
- 保持现有签名配置与数据文件格式，支持覆盖升级。


## 复现入口

~~~powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug :app:assembleDebugAndroidTest
~~~

设备测试类：

- com.omni.downloader.ui.design.RetainedTabHostTest
- com.omni.downloader.ui.design.CollectionTaskCardTest
- com.omni.downloader.AppInteractionTest

AppInteractionTest 会在目标安装的私有目录写入合成任务/设置，只用于专门的测试安装或模拟器，不能对有真实任务的用户安装运行。普通安装发布 APK 不包含测试代码或合成数据。

## 参考

- [Compose 性能最佳实践](https://developer.android.com/develop/ui/compose/performance/bestpractices)：后台准备数据、稳定键与延迟状态读取。
- [生命周期与 Compose](https://developer.android.com/topic/libraries/architecture/lifecycle)：生命周期感知流订阅。
