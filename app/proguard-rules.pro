# Preserve only reflection/serialization boundaries; app logic remains obfuscated.
-keepattributes Signature,RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
# TMDB DTOs use Kotlin reflection; keep JSON field/constructor names.
-keep class com.example.api.**Dto { *; }
-keep class com.example.api.**Response { *; }
# Retrofit 2.9 predates the full-mode suspend generic-signature rules.
-keepattributes RuntimeVisibleParameterAnnotations
-keep,allowoptimization,allowshrinking,allowobfuscation class kotlin.coroutines.Continuation
-keep,allowoptimization,allowshrinking,allowobfuscation class retrofit2.Response
-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowoptimization,allowshrinking,allowobfuscation class <3>
