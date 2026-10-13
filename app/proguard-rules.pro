# Keep kotlinx.serialization metadata for Supabase models.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class com.gng.app.** { *; }
