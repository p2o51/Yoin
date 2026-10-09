# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK tools proguard-defaults.txt.

# Keep Subsonic API response models for serialization
-keep class com.gpo.yoin.data.remote.model.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-keep class com.google.common.util.concurrent.** { *; }
-dontwarn com.google.common.util.concurrent.**

# Spotify App Remote 0.8.0 references optional Jackson serializers and
# Spotify annotations that are not needed by Yoin's runtime path.
-dontwarn com.fasterxml.jackson.databind.deser.std.StdDeserializer
-dontwarn com.fasterxml.jackson.databind.ser.std.StdSerializer
-dontwarn com.spotify.base.annotations.NotNull

# Spotify App Remote 0.8.0 discovers the host app through deprecated
# GET_SIGNATURES checks. Yoin uses a small compatibility connector that
# verifies the Spotify package with modern signing APIs, then reflectively
# constructs the SDK's package-private LocalConnector.
-keep class com.spotify.android.appremote.api.LocalConnector { *; }
-keep class com.spotify.android.appremote.internal.SdkRemoteClientConnectorFactory { *; }
-keep class com.spotify.android.appremote.internal.SpotifyLocator { *; }

# MusicKit 1.1.1 bundles and keeps JavaCPP's desktop build tools.
# Maven plugin annotations are build-time only. SLF4J is an optional logger
# selected only by org.bytedeco.javacpp.logger=slf4j, which Yoin never sets.
# Keep these narrow: missing MusicKit/JNI runtime classes must still fail R8.
-dontwarn org.apache.maven.plugins.annotations.LifecyclePhase
-dontwarn org.apache.maven.plugins.annotations.Mojo
-dontwarn org.slf4j.Logger
-dontwarn org.slf4j.LoggerFactory

# Home-screen widgets: Glance runs every widget update as a WorkManager job, and WorkManager creates its
# InputMerger and workers reflectively. R8 full mode no longer keeps default constructors implicitly, so without
# these the job fails ("OverwritingInputMerger has no zero argument constructor") and the widget spins forever.
-keepclassmembers class * extends androidx.work.InputMerger {
    public <init>();
}
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
