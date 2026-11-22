# Android 1.7.58：交叉缩放与桌面图标

首页由 95% 放大至 100%，开屏图标由 100% 缩小至 85%。沿用 250ms 冷启动最短停留、240ms 退场及原有平滑曲线，淡出和两个缩放仍共用一个时间轴。没有添加纵向平移。动画结束、中断、后台切换或系统关闭动画时，仍恢复正常尺寸并释放临时图层。

## 图标隔离

仅替换 Android 应用的系统图标入口（桌面、应用列表等系统展示位置）。清单的普通图标与圆形图标均使用独立的 `mipmap/ic_launcher_desktop`；Android 自适应图标提供白色背景与系统外形蒙版。

用户提供的 PNG 原样存放于 `app/src/main/res/drawable-nodpi/ic_desktop_art.png`，未重新绘制、裁剪或改变原图像素。SHA-256：`9da6646320b2b376cd6453bc3598cdf686115e457ca9a98e5279435dc4815b32`。自适应前景通过 XML 填充到图标边界，原图的透明外部由白底承接。

启动主题继续指定原有 `drawable/ic_omni_splash`。开屏图形、深浅色配色和首页内的图标素材均保持原样；未改动 Windows、Web 和 iOS 的图标资源。系统桌面决定最终圆形、圆角矩形等外形。

## 验证与安装包

Android 15 / API 35 的 x86_64 模拟器上，14 项仪器测试通过（`.local/splash-desktop-tests.log`，`OK (14 tests)`）。覆盖同步缩放与透明度的中间值、保持原有时长、中心轴、无纵向平移、取消、关闭动画、缺失原生图标、页面重建、前后台切换、主题切换和图层释放。

首轮测试受到同时执行的 UI 层级检查干扰，UiAutomation 连接被占用；该轮保存在 `.local/splash-desktop-tests-interrupted.log`，没有计入通过结果。排除干扰后完整重跑通过。模拟器初次开机还出现过桌面及 System UI 无响应对话框，处理后再执行 Release 检查，没有据此修改产品代码。

实际 R8 Release 的四种冷启动主题组合（系统浅色/应用深色、系统深色/应用浅色、跟随系统浅色、跟随系统深色）均检测到原有开屏图形及对应配色，最终首页显示正常，后台预热在退场结束后启动。结果位于 `.local/splash-desktop/theme-validation.json`；此次最终检查的 crash 日志为空。

桌面截图确认已显示新图片中的黑色云朵、白色箭头与白底，截图和快捷方式信息位于 `.local/splash-desktop/desktop-icon.png`、`desktop-icon-validation.json`。Pixel Launcher 为预测应用额外显示的外圈属于桌面装饰，不在图标素材内。

自动逐帧缩放拟合未通过：软件渲染模拟器录屏未捕获足够的可用退场帧。延长录制并降低模拟器分辨率后仍不足；随后恢复原分辨率。没有将该项报告为流畅度或无跳帧验收通过，也没有制作可能误导的动画效果预览。真实设备的动画流畅度、Android 8–11 分支及不同厂商桌面形状仍需实机验证。

Debug、测试 APK、R8 Release 及 Release 测试宿主构建成功。ARM64 安装包为 `release/1.7.58/OmniDownloader-1.7.58-arm64-v8a.apk`，版本 1.7.58（117），大小 60,996,756 字节；APK v2 签名校验通过，证书与 1.7.57 一致。同目录包含 `SHA256SUMS.txt`。

构建日志为 `.local/splash-desktop-build.log` 和 `.local/splash-desktop-arm64-build.log`。安装运行检查使用独立生成的 x86_64 Release 包；ARM64 APK 尚未在真机上安装运行。
