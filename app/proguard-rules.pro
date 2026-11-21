# ONNX Runtime: the JNI layer looks up Java classes/fields/methods by name.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
# Engine / ASR value classes are plain Kotlin; nothing reflective. Room/Hilt ship their own rules.
