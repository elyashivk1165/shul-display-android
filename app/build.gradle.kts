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
        versionCode = 6
        versionName = "1.0.6"

        // anon key is public-facing (safe in source) — can be overridden via -PSUPABASE_ANON_KEY
        val supabaseAnonKey = project.findProperty("SUPABASE_ANON_KEY") as String?
            ?: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6IndpZnBqZXhtY2JrZ2Zuam1tcHN0Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzE1MjI5NTQsImV4cCI6MjA4NzA5ODk1NH0.4oncY2zsUl9PntsZ2aWUetWfYdhxUicwd5MFqbBfhoQ"
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
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
