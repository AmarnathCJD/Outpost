-keep class com.jcraft.jsch.** { *; }
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
# Optional JSch desktop integrations are never selected by the Android client.
-dontwarn com.sun.jna.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**
