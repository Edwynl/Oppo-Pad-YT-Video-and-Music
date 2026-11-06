# PadTube does not expose JavaScript interfaces to web content.
# Keep WebView callbacks that are invoked by the Android framework.
-keepclassmembers class * extends android.webkit.WebChromeClient { *; }
-keepclassmembers class * extends android.webkit.WebViewClient { *; }
