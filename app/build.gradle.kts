import com.android.build.api.variant.HostTestBuilder
import com.android.build.api.variant.SigningConfigInfo
import java.util.Properties

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
