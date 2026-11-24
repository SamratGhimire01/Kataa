# JNI: native/kataa_jni.c calls these by name.
-keep class app.kataa.core.db.KataaNative { native <methods>; }
-keep class app.kataa.core.db.SqliteException { <init>(java.lang.String); }
# ONNX Runtime's Java API is reached from its own JNI code.
-keep class ai.onnxruntime.** { *; }
# JNI: native/llm/kataa_llm.cpp (the on-device AI) is called by these names.
-keep class app.kataa.core.mind.LlamaNative { native <methods>; }
