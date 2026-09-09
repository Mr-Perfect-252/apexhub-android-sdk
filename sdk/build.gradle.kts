plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
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

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
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

// ── GitHub Packages publishing ──────────────────────────────────────────────
publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "com.apexhub"
            artifactId = "sdk"
            version = "1.0.0"

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("ApexHub Android SDK")
                description.set("Official Android SDK for ApexHub — OTA updates, analytics & crash reporting")
                url.set("https://github.com/Mr-Perfect-252/apexhub-android-sdk")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/Mr-Perfect-252/apexhub-android-sdk")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}
