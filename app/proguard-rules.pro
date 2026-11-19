# Keep Android framework entry points, NotificationListenerService, and models safe under R8 full mode
-keep class com.example.BitChordApplication { *; }
-keep class com.example.MainActivity { *; }
-keep class com.example.ui.SpotifyLoginActivity { *; }
-keep class com.example.service.MusicNotificationListenerService { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Preserve models & enums
-keep class com.example.model.** { *; }

# Keep MediaMetadata / Notification reflection & MediaController metadata safe
-dontwarn android.media.**
-dontwarn android.app.Notification**
-dontwarn org.robolectric.**
