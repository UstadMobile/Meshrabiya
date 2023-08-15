# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# As per missing_rules.txt - no idea why these are being referenced via ACRA etc (see deps - com.google.auto.service:auto-service)
-dontwarn javax.annotation.processing.AbstractProcessor
-dontwarn javax.annotation.processing.SupportedOptions
-dontwarn javax.crypto.spec.ChaCha20ParameterSpec

# Kodein as per https://kosi-libs.org/kodein/7.19/framework/android.html#_proguard_configuration
-keep, allowobfuscation, allowoptimization class org.kodein.type.TypeReference
-keep, allowobfuscation, allowoptimization class org.kodein.type.JVMAbstractTypeToken$Companion$WrappingTest

-keep, allowobfuscation, allowoptimization class * extends org.kodein.type.TypeReference
-keep, allowobfuscation, allowoptimization class * extends org.kodein.type.JVMAbstractTypeToken$Companion$WrappingTest

# Bouncycastle
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }

-dontwarn javax.naming.**

#Try remove debug log calls
-assumenosideeffects interface net.luminis.quic.log.Logger {
    void logDebug(...);
    void logRaw(...);
    void logDecrypted(...);
    void debug(...);
    void debugWithHexBlock(...);
    void received(...);
    void sent(...);
    void raw(...);
    void decrypted(...);
    void encrypted(...);
    void receivedPacketInfo(...);
    void sentPacketInfo(...);
}


# As per Android Gradle Plugin 8 (2/May/2023) - probably OKHTTP related
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.conscrypt.Conscrypt$Version
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.ConscryptHostnameVerifier
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE
-dontwarn org.slf4j.impl.StaticLoggerBinder
