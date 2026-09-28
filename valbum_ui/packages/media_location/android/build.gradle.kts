group = "de.haumacher.valbum.media_location"
version = "1.0"

plugins {
    id("com.android.library")
}

android {
    namespace = "de.haumacher.valbum.media_location"
    compileSdk = flutter.compileSdkVersion

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
