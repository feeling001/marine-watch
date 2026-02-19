# Keep BLE-related classes
-keep class android.bluetooth.** { *; }

# Keep data model classes used with Gson
-keep class com.marinewatch.app.data.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
