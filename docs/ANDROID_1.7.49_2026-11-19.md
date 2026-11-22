# Android 1.7.49：修复 AppStrings 参数超限导致的启动闪退

versionCode 108。仅修复 1.7.48 启动崩溃，界面与文案内容不变。

## 根因

1.7.48 为多语言补齐新增约 267 个 `AppStrings` 字段，构造函数参数达到 517 个。JVM 方法参数槽上限为 255，kotlinc 虽然写出了类文件，但该文件非法：

```
java.lang.ClassFormatError: Too many arguments in method signature
    in class file com/omni/downloader/ui/localization/AppStrings
```

启动读取界面文案时触发 `AppStrings` 类初始化，ART 拒绝加载，进程退出。原始 250 个字段低于上限，所以此前未暴露。

## 修复

`AppStrings` 改为数组存储 + 只读 getter，由 `AppStringsBuilder` 构建，四种语言通过 `appStrings { … }` 初始化：

```kotlin
class AppStrings internal constructor(private val values: Array<String>) {
    val navHome: String get() = values[0]
    // …
}
private class AppStringsBuilder {
    lateinit var navHome: String
    // …
    fun build(): AppStrings = AppStrings(arrayOf(navHome, /* … */))
}
val ZhHansStrings = appStrings { navHome = "首页"; /* … */ }
```

构造函数参数从 517 降为 1（`String[]`），彻底脱离 255 参数上限；外部调用仍为 `strings.xxx`，所有调用点无需修改。

## 验证

- `:app:compileDebugKotlin`、`:app:assembleRelease` 成功，`lintVitalRelease` 通过。
- 在桌面 JVM 上加载 debug 与 release 两套重新生成的类并实例化 `ZhHansStrings`/`EnStrings`/`JaStrings`：无 `ClassFormatError`，取值与格式模板正确（`en.artistProgress = "%1$s · %2$d/%3$d works · …"`）。
- 正式包：versionCode 108 / 1.7.49 / arm64-v8a，debug 签名；记录见 `release/1.7.49/qa/verify.log`。
- 未做设备/模拟器画面验收（`hypervisorlaunchtype=Off`）；JVM 单元测试环境异常，未作为回归依据。

## 后续约束

新增文案时只需在数据类 getter、Builder 字段、`build()` 数组三处各加一行，并在四种语言里赋值；不要再改回「逐参数构造函数 + data class」的写法。
