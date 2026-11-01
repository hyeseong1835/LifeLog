plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.hyeseong.lifelog.collector"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.hyeseong.lifelog.collector"
        minSdk = 31   // LocationManager.requestLocationUpdates(LocationRequest) API 31. 대상은 S26U(Android 16)
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // 첫 플러그인은 저장소 plugins/를 assets로 묶어 첫 실행 때 filesDir/plugins로 푼다(GitHub URL 설치는 나중)
    sourceSets.getByName("main").assets.directories.add("../../plugins")

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "META-INF/io.netty.versions.properties")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("org.postgresql:postgresql:42.2.8")      // Postgres 직접 연결. 42.2.9+는 maxResultBuffer 파서가 java.lang.management를 써서 Android에서 못 붙는다
    implementation("app.cash.quickjs:quickjs-android:0.9.2") // JS 수집기 런타임
}
