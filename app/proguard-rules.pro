# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class **$$serializer {
    *** descriptor;
}
-keepclasseswithmembers class ru.ui99.photopult.** {
    kotlinx.serialization.KSerializer serializer(...);
}
