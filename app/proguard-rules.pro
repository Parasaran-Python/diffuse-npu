# JNI Bridge and Native Engines
-keep class com.example.sdnpu.engine.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Data Entities & Models
-keep class com.example.sdnpu.data.** { *; }
-keep class com.example.sdnpu.model.** { *; }
-keep class com.example.sdnpu.pipeline.** { *; }
-keep class com.example.sdnpu.benchmark.** { *; }
-keep class com.example.sdnpu.system.** { *; }

# Jetpack Compose & Kotlin Coroutines
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
