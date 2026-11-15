plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.autolog.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.autolog.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 12
        versionName = "0.9.0"
    }

    // AAOS Car API (CarUxRestrictionsManager, CarPropertyManager)
    useLibrary("android.car")

    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Pleos Connect Vehicle SDK: 기어, 계기판 거리, 도어
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    // 현재 위치의 주소와 내장 내비의 목적지 이름
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    // 개발용 신호 확인 화면에서만 쓴다 (출시 빌드 제외)
    debugImplementation("ai.pleos.playground:FusedLocation:1.1.3")
    // 운행 완료 음성 안내
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    // 비고에 넣는 한 마디 음성 메모
    implementation("ai.pleos.playground:SpeechToText:3.3.1")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
