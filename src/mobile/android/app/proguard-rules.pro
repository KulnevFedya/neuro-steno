# sherpa-onnx: JNI читает поля конфигов и вызывает методы по именам — ничего не переименовывать.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Наш llama.cpp JNI и вообще все классы с native-методами.
-keep class com.nuclearhack.steno.LlamaBridge { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
