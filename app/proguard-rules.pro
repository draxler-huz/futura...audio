#
# R8 / ProGuard rules for the :app module.
#
# Covers reflective access points in the Kotlin/Android layer: Hilt DI,
# Room, Moshi, Retrofit/OkHttp, Jetpack Compose, Media3, Coil, DataStore,
# EncryptedSharedPreferences, WorkManager, Wear, and the JNI bridge
# classes shipped by :engine.

-keep class com.futura.engine.NativeEngine { *; }
-keep class com.futura.engine.NativePlayerBridge { *; }
-keep class com.futura.engine.NativeUsbAudio { *; }
-keep class com.futura.engine.DspBridge { *; }
-keep class com.futura.engine.DspChainController { *; }
-keep class com.futura.engine.AudioTap { *; }
-keep class com.futura.engine.LockFreeRingBufferTap { *; }
-keep class com.futura.engine.NativeFlacScanner { *; }
-keep class com.futura.engine.NativeWaveform { *; }
-keep class com.futura.engine.ml.MlBridge { *; }
-keep class com.futura.engine.ml.MlAnalyzer { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-keep class com.futura.FuturaApp { *; }
-keep class com.futura.ui.MainActivity { *; }
-keep class com.futura.ui.MainActivityFutura { *; }
-keep class com.futura.ui.MainActivityDisguise { *; }
-keep class com.futura.ui.AlarmActivity { *; }

-keep public class * extends android.app.Service
-keep public class * extends android.app.Activity
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Application
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference
-keep public class * extends androidx.work.ListenableWorker
-keep public class * extends androidx.work.Worker
-keep public class * extends androidx.work.CoroutineWorker

-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}

-keep,allowobfuscation,allowshrinking @dagger.hilt.android.HiltAndroidApp class *
-keep,allowobfuscation,allowshrinking @dagger.hilt.android.AndroidEntryPoint class *
-keep,allowobfuscation,allowshrinking @dagger.hilt.android.lifecycle.HiltViewModel class *
-keep,allowobfuscation,allowshrinking @dagger.hilt.InstallIn class *
-keep,allowobfuscation,allowshrinking @dagger.Module class *
-keepclassmembers,allowobfuscation class * {
    @javax.inject.Inject <init>(...);
    @javax.inject.Inject <fields>;
    @javax.inject.Inject <methods>;
}
-keepclassmembers class * {
    @javax.inject.Named <fields>;
}
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Database class * { *; }
-keep @androidx.room.TypeConverter class * { *; }
-keepclassmembers @androidx.room.Entity class * {
    <fields>;
    <init>(...);
}
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public <init>();
}
-keep class androidx.room.Room { *; }
-keep class androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

-keep class com.futura.data.remote.model.** { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers @com.squareup.moshi.JsonClass class * {
    <fields>;
    <init>(...);
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <fields>;
}
-keep class * extends com.squareup.moshi.JsonAdapter { *; }
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn kotlin.reflect.jvm.internal.**

-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keep class com.futura.data.remote.interceptor.** { *; }
-keep class com.futura.data.remote.FuturaApi { *; }
-keep class com.futura.data.remote.SyncApi { *; }

-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keepclassmembers class androidx.compose.runtime.Composer { *; }
-keepclassmembers class androidx.compose.runtime.ComposerKt { *; }
-keepclassmembers class androidx.compose.runtime.internal.ComposableLambdaImpl { *; }
-dontwarn androidx.compose.**
-keep class androidx.compose.ui.tooling.preview.PreviewParameterProvider { *; }

-keep class androidx.navigation.** { *; }
-keepnames class * extends androidx.navigation.NavArgs
-dontwarn androidx.navigation.**

-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-keepclassmembers class androidx.media3.exoplayer.ExoPlayer { *; }

-keep class coil.** { *; }
-keep class coil.request.** { *; }
-keep class coil.fetch.** { *; }
-dontwarn coil.**
-dontwarn okhttp3.internal.platform.**

-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

-keep class androidx.work.** { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class com.futura.system.SelfHealJobService { *; }
-keep class com.futura.system.IntegrityWork { *; }
-dontwarn androidx.work.**

-keep class com.google.android.gms.wearable.** { *; }
-keep class com.futura.system.WearOsRemoteReceiver { *; }
-keep class com.futura.system.FuturaWearableListener { *; }
-dontwarn com.google.android.gms.**

-keep class androidx.biometric.** { *; }
-keep class com.futura.alarm.BiometricWakeUpLock { *; }
-dontwarn androidx.biometric.**

-keepclassmembers class kotlinx.coroutines.** { *; }
-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }
-dontwarn kotlinx.coroutines.**

-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}
-dontwarn kotlin.reflect.jvm.internal.**
-keep class kotlin.reflect.jvm.internal.** { *; }

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepclassmembers class * implements android.os.Parcelable {
    <fields>;
}
-keep class com.futura.alarm.AlarmSpec { *; }

-keep class android.speech.tts.** { *; }
-keep class com.futura.alarm.TextToSpeechWrapper { *; }
-keep class com.futura.alarm.VoiceDropStitcher { *; }

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions

-dontwarn java.lang.invoke.**
-dontwarn java.lang.management.**
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.element.**
-dontwarn sun.misc.Unsafe
-dontwarn org.jetbrains.annotations.**
-dontwarn org.checkerframework.**

# Temporary: R8 8.5.x throws ConcurrentModificationException in its shaking
# pass for this app's graph (CI, release minify). Skipping dead-code removal
# avoids that pass. Obfuscation and optimisation still run. Remove once R8
# is fixed or upgraded.
-dontshrink
