plugins { id("com.android.application") }
android {
    namespace = "dev.redux.controller"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.redux.controller"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes { release { signingConfig = signingConfigs.getByName("debug") } }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    // Real org.json for unit tests; the Android SDK stub throws "not mocked".
    testImplementation("org.json:json:20240303")
}
