-keepattributes *Annotation*
-keepclassmembers class * {
    native <methods>;
}
-keep class com.example.sdnpu.model.** { *; }
-keep class com.example.sdnpu.engine.** { *; }

