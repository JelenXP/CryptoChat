import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jelenxp.cryptochat"
    compileSdk = 34

    // Chatová verze má jiné applicationId, aby šla nainstalovat vedle staré
    // (offline) appky na jeden telefon. Kód (namespace) zůstává stejný -
    // applicationId (identita appky) a namespace (balíček kódu) se lišit můžou.
    defaultConfig {
        applicationId = "com.jelenxp.cryptochatonline"
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        // POZOR: versionName musí být čistě číselný (složky oddělené tečkou, každá
        // číselná). UpdateChecker.compareVersions je porovnává po složkách a nečíselnou
        // bere jako 0 - jakýkoli suffix ("1.0-chat") by se tiše zahodil.
        // SCHÉMA VERZÍ: feature = minor ("1.2"→"1.3"), OPRAVA CHYBY = patch
        // ("1.2"→"1.2.1"→"1.2.2"), ROZBITÍ KOMPATIBILITY = major ("1.x"→"2.0",
        // [important]). versionCode roste o 1 při KAŽDÉM vydání.
        // Tag na GitHubu musí sedět: "v2.2.0".
        versionName = "2.2.0"

        // Runner pro instrumentované UI testy (androidTest) - spouští se na emulátoru v CI.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Podpis release buildu vlastním klíčem. Cesty a hesla se čtou ze souboru
    // keystore.properties v kořeni projektu (viz keystore.properties.example).
    // Ten soubor NENÍ v gitu - hesla tak nikdy neopustí tvůj stroj. Pokud
    // soubor neexistuje, release spadne zpátky na debug podpis (viz buildTypes).
    signingConfigs {
        val keystorePropsFile = rootProject.file("keystore.properties")
        // Release BEZ keystoru se dřív tiše podepsal debug klíčem. To je u appky,
        // která drží klíče a historii na zařízení, nebezpečné: takové APK se nedá
        // aktualizovat přes to podepsané ostrým klíčem (jiný podpis = nutná
        // odinstalace = ztráta všech dat). Radši build rovnou zastav.
        val wantsRelease = gradle.startParameter.taskNames.any { it.contains("Release") }
        if (!keystorePropsFile.exists() && wantsRelease) {
            throw GradleException(
                "Chybí keystore.properties - release APK by se podepsalo DEBUG klíčem " +
                    "a nešlo by aktualizovat. Doplň keystore.properties + release.keystore, " +
                    "nebo sestav debug variantu (assembleDebug)."
            )
        }
        if (keystorePropsFile.exists()) {
            val keystoreProps = Properties().apply {
                keystorePropsFile.inputStream().use { load(it) }
            }
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 minifikace ZÁMĚRNĚ VYPNUTÁ. Minifikace/obfuskace R8 spouštěla
            // heuristiku antiviru (AVG hlásil „malware"); bez ní klesla detekce
            // na „suspicious". APK je proto větší (~15 MB). Podepsaná minifikovaná
            // verze (menší, obfuskovaná) se vyrábí zvlášť na nahlášení AVG false
            // positive - viz CryptoChat-forAVG-minified.apk. Až AVG whitelistne,
            // lze zvážit návrat na true.
            isMinifyEnabled = false
            isShrinkResources = false
            // Podepisuje se vždy ostrým klíčem. Když keystore.properties chybí,
            // build spadl už výš (viz signingConfigs) - žádný tichý fallback na
            // debug podpis tady schválně není.
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Robolectric potřebuje androidí prostředky (a SharedPreferences /
            // filesDir / android.util.Base64) i v čistě JVM testech.
            isIncludeAndroidResources = true
            // Ať volání nezastíněných android.* metod v čistých JVM testech
            // nepadají hned na „not mocked", ale vrátí rozumnou výchozí hodnotu.
            isReturnDefaultValues = true
        }
    }


    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // kmp-tor (resource-exec-tor) spouští tor binárku z nativeLibraryDir -
        // ta musí být rozbalená na disk (ne načtená přímo z APK).
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// Robolectric si za běhu stahuje 'android-all' jar ve FORKOVANÉ test JVM. Na
// tomhle stroji AVG Antivirus odposlouchává TLS vlastním certifikátem, kterému
// JDK nevěří - bez truststoru by download spadl na SSLHandshakeException.
// Když existuje lokální truststore (kopie cacerts + AVG cert, viz
// ~/.gradle/certs/truststore.jks), předáme ho i test JVM. Na CI soubor NEEXISTUJE,
// blok se přeskočí a použije se výchozí truststore - CI tím není dotčené.
tasks.withType<Test>().configureEach {
    val localTrust = File(System.getProperty("user.home"), ".gradle/certs/truststore.jks")
    if (localTrust.exists()) {
        jvmArgs(
            "-Djavax.net.ssl.trustStore=${localTrust.absolutePath}",
            "-Djavax.net.ssl.trustStorePassword=changeit"
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // QR code generation
    implementation("com.google.zxing:core:3.5.3")
    // QR code scanning via camera (ready-made scan Activity + result contract)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // Biometric / device-credential app lock
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.2")

    // Post-quantum key exchange (ML-KEM / FIPS 203)
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")

    // Zabudovaný Tor (kmp-tor) - aby appka došla na .onion relay bez Orbotu.
    // Pozn.: 'resource-exec-tor' (tor binárka) má vlastní verzování (408.x),
    // odlišné od 'runtime' (2.x).
    implementation("io.matthewnelson.kmp-tor:runtime:2.6.0")
    implementation("io.matthewnelson.kmp-tor:resource-exec-tor:408.22.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Unit testy. Většina běží na čistém JVM (krypto i ChatEnvelope/Pairing
    // používají java.util.Base64). Část chatové logiky ale sahá na android.util.Base64
    // (RelayCrypto) nebo SharedPreferences/filesDir (ReplayGuard, BlobQuarantine,
    // WireCompat stav) - ty jedou přes Robolectric, který android prostředí simuluje.
    testImplementation("junit:junit:4.13.2")
    // Robolectric si za běhu stahuje 'android-all' jar -> potřebuje SÍŤ. Lokální
    // offline stroj testy nespustí; autoritou je CI (build-chat-apk.yml).
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")

    // Instrumentované testy (androidTest) - běží na emulátoru/zařízení. Slouží pro
    // e2e integrační test (chat/RelayIntegrationTest): reálný Tor -> onion relay,
    // párování přes pozvánku, výměna zpráv. Runner je AndroidJUnitRunner (výše).
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
