# Add project specific ProGuard rules here.

# Keep Kotlin metadata
-keep class kotlin.Metadata { *; }

# Keep Compose classes
-keep class androidx.compose.** { *; }

# Keep data classes
-keep class com.bluelink.transfer.** { *; }

# Network classes
-keep class java.net.** { *; }
-keep class java.io.** { *; }
