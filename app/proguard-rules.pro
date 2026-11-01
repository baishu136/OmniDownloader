# yt-dlp uses Jackson-backed library models; preserve that existing reflection surface.
-keep class com.yausername.youtubedl_android.** { *; }

# These methods are invoked by page JavaScript rather than Kotlin call sites.
-keepclassmembers,allowoptimization class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep useful source locations in the mapping file for optimized crash reports.
-keepattributes SourceFile,LineNumberTable

# Commons Compress registers ZIP extra fields by Class and invokes their no-arg
# constructors reflectively while extracting the Python/FFmpeg archives.
-keep,allowobfuscation class * implements org.apache.commons.compress.archivers.zip.ZipExtraField {
    public <init>();
}
