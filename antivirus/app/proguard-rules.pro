# Security Pro - ProGuard Rules

-keepattributes Signature
-keepattributes *Annotation*

# Kotlin
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }

# Compose
-keep class androidx.compose.** { *; }

# Data classes
-keep class com.securitypro.android.data.** { *; }
