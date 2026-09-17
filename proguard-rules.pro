# WebView / Apps Script (google.script.run uses the WebView JS bridge)
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class android.webkit.WebView {
    public *;
}

# ViewBinding
-keep class com.sidilahcen.watererp.databinding.** { *; }

# Splash screen compat
-keep class androidx.core.splashscreen.** { *; }
