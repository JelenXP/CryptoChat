# ProGuard/R8 pravidla pro release build (isMinifyEnabled = true).

# --- BouncyCastle (post-kvantová výměna klíče, HKDF) ---
# Ponecháváme kompletní. Je to kryptografické jádro a knihovna místy používá
# reflexi/dynamické vyhledávání - u šifrování dáváme přednost správnosti před
# maximálním zmenšením (jinak by R8 mohl zahodit něco, co se volá reflexí).
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# --- ZXing (generování/sken QR) ---
# Knihovny mají vlastní consumer pravidla; tohle je jen pojistka proti varováním.
-dontwarn com.google.zxing.**
-dontwarn com.journeyapps.barcodescanner.**

# Kotlin metadata (pro případné reflexní použití data tříd) ponecháme.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
