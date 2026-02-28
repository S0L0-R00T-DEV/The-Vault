# The Vault - ProGuard/R8 Rules for Production Release
# =====================================================

# Keep Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Keep Room Entities and DAOs
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keepclassmembers class * {
    @androidx.room.* <fields>;
}

# Keep backup model classes to preserve backup JSON compatibility
-keep class com.vault.srd.backup.model.** { *; }

# Keep Backup + Crypto Libraries
-keep class org.bouncycastle.** { *; }
-keep class com.github.luben.zstd.** { *; }
-keep class com.squareup.moshi.** { *; }

# Suppress missing JNDI classes referenced by BouncyCastle (not on Android)
-dontwarn javax.naming.NamingEnumeration
-dontwarn javax.naming.NamingException
-dontwarn javax.naming.directory.Attribute
-dontwarn javax.naming.directory.Attributes
-dontwarn javax.naming.directory.DirContext
-dontwarn javax.naming.directory.InitialDirContext
-dontwarn javax.naming.directory.SearchControls
-dontwarn javax.naming.directory.SearchResult

# Keep Android KeyStore references
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }
-keep class android.security.keystore.** { *; }

# Keep ZXing (QR/Barcode)
-keep class com.google.zxing.** { *; }

# Keep CameraX classes
-keep class androidx.camera.core.** { *; }
-keep class androidx.camera.lifecycle.** { *; }
-keep class androidx.camera.view.** { *; }

# Keep ViewModel and StateFlow
-keep class com.vault.srd.ui.dashboard.VaultViewModel { *; }
-keepclassmembers class com.vault.srd.ui.dashboard.VaultViewModel {
    *** set***(...);
    *** get***();
    *** is***();
}

# Keep MainActivity and Aliases
-keep class com.vault.srd.MainActivity { *; }
-keep class com.vault.srd.**Alias { *; }

# Remove logging in release builds
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# Obfuscate sensitive class names (optional - can be disabled if debugging crashes)
# -obfuscationdictionary obfuscation_dict.txt
# -classobfuscationdictionary obfuscation_dict.txt
# -packageobfuscationdictionary obfuscation_dict.txt

# Keep Kotlin metadata + annotations needed for reflection/Compose
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Strip source file names + line numbers from release builds
-renamesourcefileattribute SourceFile
-keepattributes !SourceFile,!LineNumberTable

# Keep serialization
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
