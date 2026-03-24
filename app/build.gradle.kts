plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.shul.display"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.shul.display"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "1.0.22"

        // anon key is public-facing (safe in source) — blank/missing property falls back to default
        val supabaseAnonKey = (project.findProperty("SUPABASE_ANON_KEY") as String?)
            ?.takeIf { it.isNotBlank() }
            ?: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6IndpZnBqZXhtY2JrZ2Zuam1tcHN0Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzE1MjI5NTQsImV4cCI6MjA4NzA5ODk1NH0.4oncY2zsUl9PntsZ2aWUetWfYdhxUicwd5MFqbBfhoQ"
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val keystorePath = project.findProperty("KEYSTORE_PATH") as String?
            val keystorePass = project.findProperty("KEYSTORE_PASS") as String?
            val keyAlias = project.findProperty("KEY_ALIAS") as String?
            val keyPass = project.findProperty("KEY_PASS") as String?
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = keystorePass
                this.keyAlias = keyAlias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
