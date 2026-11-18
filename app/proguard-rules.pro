# Preserve WireGuard GoBackend JNI entrypoints and serialization metadata
-keep class com.wireguard.android.backend.** { *; }
-keep class com.wireguard.config.** { *; }
-keep class com.wireguard.crypto.** { *; }

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.RouteDashboard** { *; }
-keep,includedescriptorclasses class com.example.RouteServers** { *; }
-keep,includedescriptorclasses class com.example.RouteSettings** { *; }
-keep,includedescriptorclasses class com.example.model.** { *; }
