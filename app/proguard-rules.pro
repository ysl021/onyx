# ONYX release rules. Most libraries ship their own consumer rules.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# kotlinx.serialization: keep generated serializers for cached models.
-keepclassmembers @kotlinx.serialization.Serializable class app.onyx.** {
    *** Companion;
    *** serializer(...);
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.onyx.**$$serializer { *; }
-dontwarn kotlinx.serialization.**

# Media3 session services are referenced from the manifest.
-keep class app.onyx.music.LocalPlaybackService { *; }
-keep class app.onyx.buds.BudsService { *; }
-keep class app.onyx.youtube.DownloadService { *; }

# NewPipeExtractor dispatches extractors by class name and parses JSON reflectively.
-keep class org.schabi.newpipe.** { *; }
-keep class org.nanojson.** { *; }
-dontwarn org.schabi.newpipe.**

# Rhino runs YouTube's player JavaScript to decipher stream signatures.
# It ships optional desktop-only entry points (java.beans / javax.script).
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn java.beans.**
-dontwarn javax.script.**

# jaudiotagger performs format dispatch and tag reflection.
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**
-dontwarn javax.imageio.**
-dontwarn java.awt.**
-dontwarn org.jaudiotagger.tag.images.**
