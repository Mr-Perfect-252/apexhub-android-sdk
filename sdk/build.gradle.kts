import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.vanniktech.maven.publish)
}

android {
    namespace = "com.apexhub.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        targetSdk = 34
        // SDK version injected at build time
        buildConfigField("String", "SDK_VERSION", "\"1.0.0\"")
        buildConfigField("String", "DEFAULT_BASE_URL", "\"https://apex-hub-production.vercel.app\"")
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.work)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}

// ── Maven Central (Sonatype Central Portal) publishing ──────────────────────
mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()

    coordinates("io.github.mr-perfect-252", "sdk", "1.0.0")

    configure(
        com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = true,
        )
    )

    pom {
        name.set("ApexHub Android SDK")
        description.set("Official Android SDK for ApexHub — OTA updates, analytics & crash reporting")
        url.set("https://github.com/Mr-Perfect-252/apexhub-android-sdk")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("Mr-Perfect-252")
                name.set("Mr-Perfect-252")
                url.set("https://github.com/Mr-Perfect-252")
            }
        }
        scm {
            url.set("https://github.com/Mr-Perfect-252/apexhub-android-sdk")
            connection.set("scm:git:git://github.com/Mr-Perfect-252/apexhub-android-sdk.git")
            developerConnection.set("scm:git:ssh://git@github.com/Mr-Perfect-252/apexhub-android-sdk.git")
        }
    }
}
