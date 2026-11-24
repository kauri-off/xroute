plugins {
    id("com.android.application")
}

android {
    namespace = "dev.xroute"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "dev.xroute"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging.resources.merges += "META-INF/xposed/*"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
