import com.android.build.api.variant.HostTestBuilder
import com.android.build.api.variant.SigningConfigInfo
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.xroute"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "io.github.kauri_off.xroute"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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
    buildFeatures {
        buildConfig = true
        compose = true
    }
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
    onVariants(selector().withBuildType("release")) { variant ->
        val propertiesPath = providers.gradleProperty("xrouteSigningProperties")
            .getOrElse("keystore/keystore.properties")
        val propertiesFile = rootProject.layout.projectDirectory.file(propertiesPath)
        val signingInfo = providers.fileContents(propertiesFile).asBytes.orElse(byteArrayOf()).map { bytes ->
            if (bytes.isEmpty()) {
                throw GradleException("Release signing properties are missing or empty: $propertiesPath. Set -PxrouteSigningProperties to override the path.")
            }
            val properties = try {
                Properties().apply { bytes.inputStream().use(::load) }
            } catch (_: IllegalArgumentException) {
                throw GradleException("Invalid release signing properties: $propertiesPath")
            }
            fun required(name: String): String = properties.getProperty(name)?.takeIf { it.isNotBlank() }
                ?: throw GradleException("Missing $name in release signing properties: $propertiesPath")

            SigningConfigInfo(
                storeFile = propertiesFile.asFile.parentFile.resolve(required("storeFile")),
                storePassword = required("storePassword"),
                keyAlias = required("keyAlias"),
                keyPassword = required("keyPassword"),
                storeType = properties.getProperty("storeType", "PKCS12"),
            )
        }
        variant.signingConfig.from(signingInfo)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
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
