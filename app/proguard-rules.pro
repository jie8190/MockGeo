# AMap uses JNI and reflection internally. Keep its entry points and models.
-keep class com.amap.** { *; }
-keep class com.autonavi.** { *; }
-keep class com.loc.** { *; }
# Optional high-precision RTK extension absent from AMap's public SDK bundle.
# MockGeo obtains device location through Android LocationManager, not this extension.
-dontwarn com.amap.ams.gnss.GnssSoftLocator

# osmdroid chooses archive/cache helpers through reflection.
-keep class org.osmdroid.** { *; }

# JNI entry points in the optional native bridge must retain their JVM names.
-keep class com.mockgeo.core.engine.NativeArtBridge { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
