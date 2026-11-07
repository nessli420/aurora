-dontobfuscate
-keepattributes SourceFile,LineNumberTable
-keep class com.aurora.music.** { *; }

# rhino (newpipe youtube deobfuscation) loads its interpreter, regexp, vm bridge and typed arrays by name
-keep class org.mozilla.javascript.** { *; }
-dontwarn java.beans.**,javax.script.**,jdk.dynalink.**

# protobuf-lite resolves message fields by name (newpipe xtags and playlist continuation protos)
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
# newpipe serializable lambdas, android has no SerializedLambda
-dontwarn java.lang.invoke.SerializedLambda

# jaudiotagger creates id3 frame bodies, copies and asf readers by reflection, its jcodec mp4 boxes too
-keep class org.jaudiotagger.tag.** { <init>(...); }
-keep class org.jaudiotagger.audio.asf.io.** { <init>(); }
-keep class org.jcodec.containers.mp4.boxes.** { <init>(...); }
-dontwarn java.awt.**,javax.imageio.**,javax.swing.**,sun.security.action.**

# onnx runtime jni builds tensors, infos and errors through constructors no java code calls
-keep class ai.onnxruntime.** { *; }

# ffmpeg renderer is only reached by DefaultRenderersFactory reflection and a miss falls back silently
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer { <init>(android.os.Handler, androidx.media3.exoplayer.audio.AudioRendererEventListener, androidx.media3.exoplayer.audio.AudioSink); }

# jsch (decent sftp, unreachable) optional deps
-dontwarn com.sun.jna.**,org.apache.logging.log4j.**,org.slf4j.**,org.ietf.jgss.**,org.newsclub.net.unix.**,org.bouncycastle.**
