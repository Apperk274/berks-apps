plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val appVersionCode = 1
val appVersionName = "0.1.0"

val releaseSigning = listOf(
    "BERK_RELEASE_STORE_FILE",
    "BERK_RELEASE_STORE_PASSWORD",
    "BERK_RELEASE_KEY_ALIAS",
    "BERK_RELEASE_KEY_PASSWORD",
).associateWith { providers.gradleProperty(it).orNull }
val releaseSigningReady = releaseSigning.values.none { it.isNullOrBlank() }

android {
    namespace = "dev.personal.berksapps"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.personal.berksapps"
        minSdk = 30
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            if (releaseSigningReady) {
                storeFile = file(releaseSigning.getValue("BERK_RELEASE_STORE_FILE")!!)
                storePassword = releaseSigning.getValue("BERK_RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigning.getValue("BERK_RELEASE_KEY_ALIAS")
                keyPassword = releaseSigning.getValue("BERK_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.conscrypt.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.libadb.android)

    testImplementation(libs.json)
    testImplementation(libs.junit)
}

gradle.taskGraph.whenReady {
    val signsRelease = allTasks.any { task ->
        task.project == project && task.name.endsWith("Release") &&
            (task.name.startsWith("package") || task.name.startsWith("validateSigning"))
    }
    if (signsRelease && !releaseSigningReady) {
        throw GradleException(
            "Release signing is not configured. Set BERK_RELEASE_STORE_FILE, " +
                "BERK_RELEASE_STORE_PASSWORD, BERK_RELEASE_KEY_ALIAS and BERK_RELEASE_KEY_PASSWORD " +
                "in the user-level gradle.properties (see the Releasing section of the README)."
        )
    }
}
