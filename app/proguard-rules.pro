# ARCore loads native classes reflectively.
-keep class com.google.ar.core.** { *; }
-dontwarn com.google.ar.core.**

# ML Kit model bindings.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
