plugins {
    id("com.android.application") version "8.5.2" apply false
    // Kotlin 2.x je nutný kvůli kmp-tor (2.6.0 je postavený na Kotlinu 2.x).
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    // Od Kotlinu 2.0 se Compose kompilátor deklaruje jako Gradle plugin
    // (nahrazuje composeOptions { kotlinCompilerExtensionVersion }).
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}
