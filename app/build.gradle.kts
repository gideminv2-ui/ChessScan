plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.example.chessscan"
    compileSdk = 35
    defaultConfig { applicationId = "com.example.chessscan"; minSdk = 26; targetSdk = 35; versionCode = 2; versionName = "2.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.activity:activity:1.9.3")
    implementation("androidx.core:core:1.13.1")
    implementation("com.github.bhlangonijr:chesslib:1.3.4")
}
