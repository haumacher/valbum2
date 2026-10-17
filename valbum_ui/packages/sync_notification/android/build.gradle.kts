group = "de.haumacher.valbum.sync_notification"
version = "1.0"

plugins {
    id("com.android.library")
}

android {
    namespace = "de.haumacher.valbum.sync_notification"
    compileSdk = flutter.compileSdkVersion

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
