# MaaCore C entry points and JNA callbacks are looked up by name.
-keep interface com.maadroid.app.engine.arknights.core.MaaCoreLibrary { *; }
-keep interface com.maadroid.app.engine.arknights.core.AsstApiCallback { *; }
-keep class com.sun.jna.* { *; }
-keepclassmembers class * extends com.sun.jna.Structure { <fields>; }
-keepclassmembers class * implements com.sun.jna.Callback { <methods>; }
-dontwarn java.awt.**

# Preserve IPC descriptors and generated Stub entry points across the module move.
-keep class com.maadroid.app.MaaCoreService { *; }
-keep class com.maadroid.app.MaaCoreService$Stub { *; }
-keep class com.maadroid.app.MaaCoreCallback { *; }
-keep class com.maadroid.app.MaaCoreCallback$Stub { *; }
