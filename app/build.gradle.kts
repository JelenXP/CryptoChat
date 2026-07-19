import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jelenxp.cryptochat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jelenxp.cryptochat"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "3.0"

        // Runner pro instrumentované UI testy (androidTest) - spouští se na emulátoru v CI.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Podpis release buildu vlastním klíčem. Cesty a hesla se čtou ze souboru
    // keystore.properties v kořeni projektu (viz keystore.properties.example).
    // Ten soubor NENÍ v gitu - hesla tak nikdy neopustí tvůj stroj. Pokud
    // soubor neexistuje, release spadne zpátky na debug podpis (viz buildTypes).
    signingConfigs {
        val keystorePropsFile = rootProject.file("keystore.properties")
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
            // Když existuje keystore.properties, podepíše se vlastním release
            // klíčem; jinak fallback na debug klíč (aby build fungoval i bez
            // keystoru, např. v CI). Debug podpis stačí na osobní instalaci,
            // ne na Google Play.
            signingConfig = if (rootProject.file("keystore.properties").exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
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

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Unit testy (běží na čistém JVM - krypto používá java.util.Base64, ne
    // android.util.Base64, takže není potřeba Robolectric ani emulátor).
    testImplementation("junit:junit:4.13.2")

    // Instrumentované UI testy (androidTest) - ověří, že se appka spustí a projde
    // základní obrazovka. Běží na emulátoru v CI (workflow ui-tests.yml), lokálně
    // offline se nestahují ani nespouští (assembleDebug/Release je nepotřebuje).
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
