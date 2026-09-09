# Keep all ApexHub SDK public API classes
-keep class com.apexhub.sdk.ApexHubUpdater { *; }
-keep class com.apexhub.sdk.ApexHubConfig { *; }
-keep class com.apexhub.sdk.UpdateInfo { *; }
-keep class com.apexhub.sdk.UpdateCheckResult { *; }
-keep class com.apexhub.sdk.UpdateStrategy { *; }
-keep class com.apexhub.sdk.ApexHubException { *; }

# Keep Gson model serialization
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn com.google.gson.**
