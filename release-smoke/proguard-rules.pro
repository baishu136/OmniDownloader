# This APK is only the black-box test harness. Keep its independent JUnit,
# UiAutomator and Kotlin runtime intact; optimization applies to the target APK.
-keep class ** { *; }
-dontoptimize
# Optional Error Prone static-analysis annotation; not needed at runtime.
-dontwarn com.google.errorprone.annotations.MustBeClosed
