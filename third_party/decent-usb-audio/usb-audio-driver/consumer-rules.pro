# JNI: native methods and the classes/fields the C++ side looks up by name.
-keep class com.decent.usbaudio.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
