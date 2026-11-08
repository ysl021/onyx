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

# jaudiotagger performs format dispatch and tag reflection.
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**
-dontwarn javax.imageio.**
-dontwarn java.awt.**
-dontwarn org.jaudiotagger.tag.images.**
