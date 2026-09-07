# Readable stack traces on Play (mapping.txt is uploaded by the Play plugin).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ONNX Runtime: the native library looks these classes/fields/methods up by name
# via JNI. The AAR's own proguard.txt only covers ai.onnxruntime.telemetry.*.
-keep class ai.onnxruntime.** { *; }

# kotlinx-serialization needs no rules here: kotlinx-serialization-core 1.9.0
# ships consumer rules (META-INF/com.android.tools/proguard) that already keep
# Companion, serializer() and the $$serializer descriptor field.

# ML Kit / Firebase components: registrars are found by name from manifest
# metadata and instantiated reflectively. firebase-components' own rule keeps
# the classes, but R8 full mode drops their no-arg constructors — keep them.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
