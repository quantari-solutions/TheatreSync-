# Referenced only by name from AndroidManifest <meta-data>, so R8 can't see it's used.
-keep class com.theatresync.app.CastOptionsProvider { *; }

# kotlinx.serialization: keep generated serializers for protocol payloads.
-keepclassmembers class com.theatresync.core.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.theatresync.core.**$$serializer { *; }

# The sync engine is small and uses kotlinx.serialization: keep it whole so code
# shrinking can never break the protocol in the optimised build.
-keep class com.theatresync.core.** { *; }

# Vosk speech recognition calls native code through JNA, which finds classes and methods by name.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**

# YouTube headphones: the page calls back into the app by method name.
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
