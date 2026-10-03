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
