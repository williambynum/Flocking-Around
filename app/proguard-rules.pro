# ARCore loads native classes reflectively.
-keep class com.google.ar.core.** { *; }
-dontwarn com.google.ar.core.**

# ML Kit model bindings.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# The Anthropic SDK serialises its request/response models with Jackson reflectively.
# anthropic-java-core ships its own keep rules, which R8 picks up automatically; these cover
# the Jackson internals it reaches through and the JVM-only classes it references but that
# Android never loads.
-keep class com.anthropic.** { *; }
-dontwarn com.anthropic.**
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
# The SDK's victools JSON-schema generator backs addTool(Class<T>) — the feature that derives
# a tool schema from a Java class. It reaches for java.lang.reflect.AnnotatedType, which does
# not exist on Android. This app never calls addTool, so the code is unreachable and the
# warnings are safe to suppress. Anyone adding tool use here must build the schema by hand
# instead; addTool(Class) would fail at runtime on Android.
-dontwarn com.github.victools.**
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedType

-dontwarn java.beans.**
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
