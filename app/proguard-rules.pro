# Preserve generic signatures, annotations, enclosing methods, and line numbers for Gson, Room, Kotlin, and crash diagnostics.
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault, SourceFile, LineNumberTable

# Preserve Kotlin metadata so reflection, Room, Compose, and Gson handle Kotlin nullability/defaults properly.
-keep class kotlin.Metadata { *; }

# Gson serialization / TypeToken generic type arguments
-keep class com.google.gson.stream.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Room entities, DAOs, database implementations, and query projections
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }

# Application domain models, Room entities/projections, WebDAV/API/SFTP sync DTOs, updater, AI, TTS, diagnostics, and reference models
-keep class io.github.notebook.android.data.** { *; }
-keep class io.github.notebook.android.sync.** { *; }
-keep class io.github.notebook.android.update.** { *; }
-keep class io.github.notebook.android.ai.** { *; }
-keep class io.github.notebook.android.tts.** { *; }
-keep class io.github.notebook.android.diagnostics.** { *; }
-keep class io.github.notebook.android.reminder.** { *; }
-keep class io.github.notebook.android.reference.** { *; }
-keep class io.github.notebook.android.audio.** { *; }
-keep class io.github.notebook.android.MarkdownDocument* { *; }
-keep class io.github.notebook.android.NotebookAssetSyntax* { *; }
-keep class io.github.notebook.android.NotebookReferenceSyntax* { *; }

# WorkManager workers instantiated reflectively
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# AndroidX Security Crypto / Google Tink (reflective key managers & primitives)
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# JSch SFTP legacy migration client (reflective cipher, MAC, KEX, and key algorithms)
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.bouncycastle.**
-dontwarn org.newsclub.**
-dontwarn com.sun.jna.**
-dontwarn java.lang.management.**

# OkHttp / Okio optional platform TLS providers
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Markwon & CommonMark Markdown AST nodes and visitors
-keep class io.noties.markwon.** { *; }
-keep class org.commonmark.** { *; }
