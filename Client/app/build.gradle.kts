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
        versionCode = 1
        // POZOR: versionName musí být čistě číselný ("1.0", "1.1", "2.0"). Porovnává
        // ho UpdateChecker.compareVersions po složkách oddělených tečkou a nečíselnou
        // složku bere jako 0 - jakýkoli suffix ("1.0-chat") by se tiše zahodil.
        // Tag vydání na GitHubu musí sedět: "v1.1" nebo "1.1".
        versionName = "1.0"

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

    // Unit testy (běží na čistém JVM - krypto používá java.util.Base64, ne
    // android.util.Base64, takže není potřeba Robolectric ani emulátor).
    testImplementation("junit:junit:4.13.2")

    // Pozn.: instrumentované UI testy (androidTest) chatová verze zatím nemá.
    // Původní testovací závislosti (compose-ui-test, ui-test-manifest) byly
    // odebrány, protože nejsou v offline Gradle cache a blokovaly by lokální
    // assembleDebug. Až bude potřeba, přidají se zpět (v CI, kde je síť).
}
