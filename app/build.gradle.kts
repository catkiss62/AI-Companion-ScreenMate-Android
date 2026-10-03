plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.catkiss.screenmate"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.catkiss.screenmate"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.1.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs.getByName("debug") {
        System.getenv("SCREENMATE_TEST_KEYSTORE")?.let { path ->
            storeFile = file(path)
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true; animationsDisabled = true }
    lint { abortOnError = true }
}
dependencies {
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
