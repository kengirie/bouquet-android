# Add project specific ProGuard rules here.

# Keep line numbers for stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Bouquet release rules ────────────────────────────────────────────────

# Keep Quartz event classes — they're deserialized via Jackson + EventFactory
# routing on `kind`, which uses reflection on subtypes. Without this, R8 might
# strip unreferenced subclasses (RootSiteEvent, NamedSiteEvent, etc.) since
# nothing calls their constructors directly in app code.
-keep class com.vitorpamplona.quartz.** { *; }
-keep interface com.vitorpamplona.quartz.** { *; }
-dontwarn com.vitorpamplona.quartz.**

# Jackson annotations — used by Quartz's deserializers
-keepattributes *Annotation*, EnclosingMethod, Signature, InnerClasses
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.databind.**
-dontwarn com.fasterxml.jackson.**
-keep class kotlin.Metadata { *; }
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.JsonProperty *;
    @com.fasterxml.jackson.annotation.JsonCreator *;
}

# OkHttp + Okio — both ship with consumer rules but be defensive
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# NanoHTTPD — uses reflection sparingly; keep public API
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# secp256k1-kmp — JNI bindings; loadLibrary uses reflection
-keep class fr.acinq.secp256k1.** { *; }
-dontwarn fr.acinq.secp256k1.**

# kotlinx.coroutines internals
-dontwarn kotlinx.coroutines.**

# Compose Runtime — already has consumer rules, no extra config needed.

# Generic Kotlin / serialization defenses
-keepclassmembers class **$Companion {
    *;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
}
