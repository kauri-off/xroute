import com.android.build.api.variant.HostTestBuilder

plugins {
    id("com.android.application")
}

android {
    namespace = "dev.xroute"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "io.github.kauri_off.xroute"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        create("integration") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".integration"
            matchingFallbacks += "debug"
        }
    }
    testBuildType = "integration"
    buildFeatures.buildConfig = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging.resources.merges += "META-INF/xposed/*"
}

androidComponents {
    beforeVariants(selector().withBuildType("debug")) { variant ->
        variant.hostTests.getValue(HostTestBuilder.UNIT_TEST_TYPE).enable = true
    }
}

dependencies {
    androidTestImplementation("io.github.libxposed:api:102.0.0")
    androidTestImplementation("com.google.android.gms:play-services-location:21.3.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("io.github.libxposed:interface:102.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.json:json:20240303")
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
