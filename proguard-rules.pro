# ProGuard rules for release builds (createReleaseDistributable / packageReleaseDmg).
# Applied on top of the Compose Gradle plugin's defaults, which already cover Kotlin, skiko,
# kotlinx-coroutines(-swing), kotlinx-datetime, kotlinx-serialization and Compose runtime.
# Obfuscation is off (see build.gradle.kts), so stack traces stay readable and class/package
# names - and therefore class-relative resource lookups - are unchanged. Shrinking is what drops
# the unused Material extended icons (~38 MB jar).

# --- App ----------------------------------------------------------------------------------------
# Entry point is kept by the plugin; keep our own code's names intact for readable crash logs.
-keepnames class net.robog.budgetinator.** { *; }
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# --- Compose / skiko ----------------------------------------------------------------------------
# skiko's native code calls back into these through JNI.
-keep class org.jetbrains.skiko.** { *; }
-keep class org.jetbrains.skia.** { *; }
-dontwarn org.jetbrains.skiko.**
# Compose desktop uses JetBrains Runtime APIs only when running on JBR.
-dontwarn com.jetbrains.**
-dontnote com.jetbrains.**
# Optional/compile-only annotations and platform classes referenced by AndroidX/Compose.
-dontwarn androidx.annotation.**
-dontwarn org.jspecify.annotations.**
-dontwarn javax.annotation.**
-dontwarn android.**

# --- kotlinx.coroutines -------------------------------------------------------------------------
# Main dispatcher is discovered through ServiceLoader (META-INF/services).
-keep class kotlinx.coroutines.swing.SwingDispatcherFactory { *; }
-keep class * implements kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class * implements kotlinx.coroutines.CoroutineExceptionHandler { *; }
-dontwarn kotlinx.coroutines.debug.**
-dontwarn reactor.blockhound.**

# --- kotlinx.serialization (draft save files) ---------------------------------------------------
# Standard rules from https://github.com/Kotlin/kotlinx.serialization/blob/master/rules/common.pro
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class **$$serializer { *; }
# Keep every @Serializable class whole so field/constructor shapes match saved drafts across
# releases even if the optimizer would otherwise merge or inline something.
-keep @kotlinx.serialization.Serializable class ** { *; }
-dontnote kotlinx.serialization.**
-dontwarn kotlinx.serialization.internal.ClassValueReferences

# --- Apache PDFBox / FontBox (PDF import) -------------------------------------------------------
# PDFBox loads glyph lists, CMaps, AFM fonts and ICC profiles as class-relative resources and
# instantiates filters/fonts reflectively, so keep it whole. Its optional dependencies (JAI
# image codecs, JBIG2, Bouncy Castle for encrypted PDFs, OSGi) are not bundled.
-keep class org.apache.pdfbox.** { *; }
-keep class org.apache.fontbox.** { *; }
-keep class org.apache.commons.logging.** { *; }
-dontwarn org.apache.pdfbox.**
-dontwarn org.apache.fontbox.**
-dontwarn org.apache.commons.logging.**
-dontwarn org.bouncycastle.**
-dontwarn com.github.jaiimageio.**
-dontwarn com.sun.media.imageio.**
-dontwarn org.apache.jempbox.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.avalon.**
-dontwarn org.apache.log.**
-dontwarn org.osgi.**
-dontnote org.apache.pdfbox.**
-dontnote org.apache.fontbox.**
-dontnote org.apache.commons.logging.**

# --- General ------------------------------------------------------------------------------------
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
# Skip optimizations known to miscompile Kotlin/Compose bytecode.
-optimizations !method/specialization/parametertype,!method/specialization/returntype,!class/merging/*,!code/allocation/variable
