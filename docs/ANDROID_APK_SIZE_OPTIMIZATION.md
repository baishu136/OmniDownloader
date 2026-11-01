# Android APK 体积优化：1.5.8

## 结果

| ARM64 Release | APK 文件字节数 | 文件体积 |
| --- | ---: | ---: |
| 1.5.7 | 71,799,009 | 68.47 MiB |
| 1.5.8 | 55,925,254 | 53.33 MiB |
| 减少 | 15,873,755 | 15.14 MiB / 22.11% |

这是 APK 文件大小，不是运行库解压后的应用数据占用。发布 ABI 仍为 arm64-v8a，签名配置和任务 JSON 格式保持兼容，versionCode 从 48 增为 49。

## 实施

1. Release 启用 R8 代码压缩、优化和混淆，以及资源裁剪，继续使用 proguard-android-optimize.txt。
2. 移除没有被任何下载请求选择、也没有初始化的可选 Aria2c 依赖。当前请求使用 yt-dlp 内置下载器；FFmpegExecutor 中对旧 Aria2c 目录的可选兼容不会安装或加载它。
3. 移除没有源码使用的 ui-tooling-preview 直接运行时依赖。Debug 工具仍留在 Debug 依赖中。
4. 保留 yt-dlp 库的 Jackson 反射模型表面，以及 @JavascriptInterface 的桥接方法。
5. 首轮真正冷启动发现 Commons Compress 的 ZIP 附加字段通过反射创建，R8 将默认构造器裁掉。为实现 ZipExtraField 的类型保留无参构造器，已验证冷解压恢复正常。没有给整个应用添加全量保留规则。
6. FFmpeg、ffprobe、Python、QuickJS 的六个 ARM64 文件逐项 SHA-256 与 1.5.7 相同，包括两份完整运行库压缩载荷；没有裁剪编解码器、Python 模块或改为联网补装运行库。

## 包内容对比

下表为 ZIP 条目压缩后的字节数，不含 ZIP 目录、签名和对齐开销。

| 分类 | 1.5.7 | 1.5.8 |
| --- | ---: | ---: |
| DEX | 12,113,655（3 个） | 1,838,963（1 个） |
| 原生可执行文件和运行库 | 55,328,259（8 个） | 50,147,242（6 个） |
| 资源与资源表 | 4,238,442 | 3,841,639 |

原生运行库现在占大多数 APK 内容。继续大幅缩小需要改变 FFmpeg 构建或运行库分发方式，本次保留现有媒体处理能力。

## 验证

- 14 项 JVM 单元测试通过。
- 默认 ARM64 Release 构建和完整 Release Lint 通过（无 error，仍有项目 warning）。
- 交付的 ARM64 APK：清空专用模拟器应用数据后，Python/FFmpeg/yt-dlp 冷解压初始化、历史任务 JSON 恢复、首页/任务/设置切换通过。
- 六个保留的 ARM64 核心文件与旧版字节一致，详见 [ANDROID_APK_SIZE_RESULTS.json](ANDROID_APK_SIZE_RESULTS.json)。
- 当前没有 ARM64 真机。x86_64 模拟器的 ARM64 程序翻译器与载荷中的动态库存在架构冲突，因此没有把翻译器失败当作正式包原生功能失败，也没有声称完成 ARM64 真机转码测试。
- 使用同一份优化后应用代码、同版本上游 x86_64 运行库生成专门 QA 包，原生测试全部通过：Python/yt-dlp 版本检查、生成含音视频 MP4、提取 MP3、生成 GIF、去音轨并用 ffprobe 验证仅含视频轨，以及 yt-dlp 内置下载器从本地 HTTP 服务下载并逐字节比对。测试不依赖公网或账户。

## 复现

默认交付构建：

~~~powershell
.\gradlew.bat :app:assembleRelease :app:testDebugUnitTest :app:lintRelease
~~~

混淆正式包使用独立 release-smoke 测试 APK，避免把依赖原始 Compose/应用类名的 Debug 测试直接附加到 Release。该模块为测试端单独封装了 Kotlin 和 tracing API，避免 AGP 将这些依赖从测试 APK 去重、而它们又被正式包 R8 内联或删除；这些测试依赖不会进入交付 APK。

~~~powershell
.\gradlew.bat :release-smoke:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb install -r release-smoke/build/outputs/apk/release/release-smoke-release.apk
adb shell am instrument -w -e skipNativeExecution true -e class com.omni.downloader.OptimizedReleaseTest com.omni.downloader.releasesmoke/androidx.test.runner.AndroidJUnitRunner
~~~

在匹配 ABI 的设备上去掉 skipNativeExecution 参数即可运行全部原生测试。为 x86_64 模拟器构建测试包：

~~~powershell
.\gradlew.bat :app:assembleRelease :release-smoke:assembleRelease -PomniTestAbi=x86_64
~~~

测试会写入合成任务和设置，只能在专用测试安装上运行。冷启动测试前需清空该测试安装的数据并授予通知权限。模拟器测试后应重新执行不带 omniTestAbi 参数的默认构建，恢复 ARM64 交付输出。当前已完成恢复，输出与交付包 SHA-256 一致。

## 交付文件

- OmniDownloader-v1.5.8-release.apk
- OmniDownloader_LATEST.apk（同一份 ARM64 Release）
- OmniDownloader-v1.5.8-SHA256.txt
- OmniDownloader-v1.5.8-mapping.zip（R8 mapping.txt，调试崩溃用，不随 APK 安装）

APK SHA-256：33B7455043FC43D4F788B6FB0ED15F16F84E13D8ACDF6C9AB5C49FCAC437F079

参考：[Android 官方 R8 与资源优化配置](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)。
