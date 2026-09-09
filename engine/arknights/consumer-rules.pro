# MaaCore C entry points and JNA callbacks are looked up by name.
-keep interface com.aliothmoon.maadroid.engine.arknights.core.MaaCoreLibrary { *; }
-keep interface com.aliothmoon.maadroid.engine.arknights.core.AsstApiCallback { *; }
-keep class com.sun.jna.* { *; }
-keepclassmembers class * extends com.sun.jna.Structure { <fields>; }
-keepclassmembers class * implements com.sun.jna.Callback { <methods>; }
-dontwarn java.awt.**

# Preserve IPC descriptors and generated Stub entry points across the module move.
-keep class com.aliothmoon.maadroid.MaaCoreService { *; }
-keep class com.aliothmoon.maadroid.MaaCoreService$Stub { *; }
-keep class com.aliothmoon.maadroid.MaaCoreCallback { *; }
-keep class com.aliothmoon.maadroid.MaaCoreCallback$Stub { *; }
