plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.vandatgsts.mylibrary"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    api("org.bouncycastle:bcprov-jdk15to18:1.84")
    api("org.bouncycastle:bcpkix-jdk15to18:1.84")
    api("org.bouncycastle:bcutil-jdk15to18:1.73")
// for jpeg2000 decode/encode
    compileOnly("com.github.Tgo1014:JP2ForAndroid:1.0.4")

}