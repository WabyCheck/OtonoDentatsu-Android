 # OtonoDentatsu-Android

 Android foreground service client that receives Opus over UDP and plays it via AudioTrack.

 Features:
 - ForegroundService with notification and Stop action
 - Jitter buffer and decoder thread
 - Background stability (WiFiLock HIGH_PERF, PARTIAL_WAKE_LOCK)
 - Dynamic prebuffer when screen off/on

 Build:
 - Open in Android Studio (Gradle kts project)
 - Build APK or run on device

 Permissions:
 - INTERNET, RECORD_AUDIO (if needed), FOREGROUND_SERVICE, POST_NOTIFICATIONS (13+), WAKE_LOCK

 License: MIT
