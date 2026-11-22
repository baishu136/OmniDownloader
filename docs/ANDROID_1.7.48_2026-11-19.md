# Android 1.7.48：设置选择器去边框与界面多语言补全

versionCode 107。仅修改 Android 端。

## 变更

1. **设置页描边**：语言选择与主题模式共用的下拉控件从 `OutlinedButton` 改为无描边的 `TextButton`，保留文字、箭头、34dp 高度、按压涟漪与菜单弹出逻辑；不再绘制 `colorScheme.outline` 的 1dp 描边。账号页复用同一组件的平台下拉同步变为无边框。
2. **界面多语言**：`AppStrings` 新增约 190 个键并补齐四种语言；替换批量下载、GIF、图文、任务、合集、中转站、抖音浏览器、平台登录、媒体捕获、图片查看器、首页与导航中的硬编码中文。
3. **动态文案**：`DownloadType`、`AudioFormat`、`TaskStatus` 的中文 `label` 不再直接显示，改由 `AppStrings` 提供；支持平台名称在保留内部中文标识的同时按语言显示。
4. **ViewModel 与通知**：`MainViewModel` 按已保存语言初始化并在切换时同步，Snackbar 文案本地化；`DownloadService` 的前台/进度/完成通知与通知渠道名称跟随应用内语言。
5. 顺带修正图文网格中「已选 总数 / 已选数」顺序颠倒。

## 构建与验证

- `:app:compileDebugKotlin`、`:app:assembleDebug`、`:app:assembleRelease` 全部成功；`lintVitalRelease` 通过。
- 正式包：versionCode 107 / versionName 1.7.48 / arm64-v8a，使用项目既有 debug 签名，验签与 SHA-256 见 `release/1.7.48/qa/verify.log` 与 `SHA256SUMS.txt`。
- **未完成设备/模拟器界面验收**：本机 `hypervisorlaunchtype=Off`，模拟器启动失败（含 `-accel off` 软件加速），无实体设备连接。建议后续在可用环境检查设置页中/英/日文案与两个下拉无描边。
- 本机 JVM 单元测试全部因 `ClassNotFoundException` 无法加载（环境问题），未作为回归依据。
- 未更新 Windows/iOS/Web 与 GitHub。

## 已知残留

引擎与数据层直接抛出的警告/错误（`qualityWarning`、Google 档案异常、底层下载错误等）仍为中文诊断文本，需要错误码到多语言的映射后才能完整消除。

交付目录：`release/1.7.48/`。
