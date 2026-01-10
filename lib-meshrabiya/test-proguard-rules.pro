# Test-specific ProGuard rules for Meshrabiya lib

# Keep Mockito classes and annotations
-keep class org.mockito.** { *; }
-keep interface org.mockito.** { *; }
-keep class org.mockito.internal.** { *; }
-keepattributes *Annotation*

# Keep ByteBuddy (used by Mockito)
-keep class net.bytebuddy.** { *; }
-keep interface net.bytebuddy.** { *; }
-dontwarn net.bytebuddy.**

# Keep test framework classes
-keep class org.junit.** { *; }
-keep class junit.** { *; }
-keep class org.hamcrest.** { *; }

# Keep Kotlin reflection for testing
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.**

# Keep coroutines test classes
-keep class kotlinx.coroutines.test.** { *; }
-keep class kotlinx.coroutines.** { *; }

# Preserve test method names and line numbers
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep all test classes
-keep class **.*Test { *; }
-keep class **.*Test$* { *; }

# Keep classes that might be accessed via reflection in tests
-keep class com.ustadmobile.meshrabiya.** { *; }

# Disable optimization for test builds
-dontoptimize
-dontobfuscate

# Keep synthetic bridge methods
-keepclassmembers class * {
    synthetic <methods>;
}

# Allow dynamic agent loading for Java 21
-keepattributes RuntimeVisibleAnnotations
-keep class * extends java.lang.instrument.ClassFileTransformer { *; }
