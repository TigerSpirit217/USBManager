# Android platform contracts adapted from AGP's optimized default rules.
# Unlike its blanket View getter/setter rule, this app uses ViewPropertyAnimator,
# typed Property objects and ValueAnimator, not string-based custom View properties.
# Library-specific reflective/animated properties retain their consumer keep rules.
# Manifest/XML entry points are also retained by AGP's generated rules.

-allowaccessmodification

# Preserve runtime annotations and generic/inner-class reflection used by libraries.
-keepattributes AnnotationDefault,EnclosingMethod,InnerClasses,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeVisibleTypeAnnotations,Signature

# JNI member names are an external contract.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Retain XML onClick callbacks and parcel reconstruction contracts.
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Enum reflection can be used by Android/library saved-state code.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# AndroidX supplies its own @Keep rules. Retain the equivalent legacy annotation contract.
-keep class android.support.annotation.Keep
-keep @android.support.annotation.Keep class * { *; }
-keepclasseswithmembers class * {
    @android.support.annotation.Keep <methods>;
}
-keepclasseswithmembers class * {
    @android.support.annotation.Keep <fields>;
}
-keepclasseswithmembers class * {
    @android.support.annotation.Keep <init>(...);
}

# Match the platform defaults for optional/version-specific library references.
-dontnote android.support.**
-dontnote androidx.**
-dontwarn android.support.**
-dontwarn androidx.**
-dontnote org.apache.http.**
-dontnote android.net.http.**
-dontnote java.lang.invoke.**
