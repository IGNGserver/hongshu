plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val hongshuVersion = file("../../VERSION").readText().trim()
val versionMatch =
    Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-(alpha|beta|rc)\\.(\\d+))?$").matchEntire(hongshuVersion)
        ?: error("Invalid VERSION")

val (major, minor, patch, channel, iteration) = versionMatch.destructured

val channelCode =
    if (channel.isEmpty()) 9999L
    else {
        val number = iteration.toLong()
        require(number in 1..2999) { "Prerelease iteration exceeds Android coding range" }
        number +
            when (channel) {
                "alpha" -> 0
                "beta" -> 3000
                else -> 6000
            }
    }

require(minor.toLong() < 100 && patch.toLong() < 100) { "Version exceeds Android coding range" }

val releaseCode =
    major.toLong() * 100000000 + minor.toLong() * 1000000 + patch.toLong() * 10000 + channelCode

require(releaseCode in 1..2100000000) { "Android versionCode out of range" }

android {
    namespace = "io.hongshu.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.hongshu.app"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseCode.toInt()
        versionName = hongshuVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            isMinifyEnabled = false
            val keystorePath = providers.gradleProperty("androidKeystorePath").orNull
            val keystorePassword = providers.gradleProperty("androidKeystorePassword").orNull
            val keyAlias = providers.gradleProperty("androidKeyAlias").orNull
            val keyPassword = providers.gradleProperty("androidKeyPassword").orNull
            if (
                keystorePath != null &&
                    keystorePassword != null &&
                    keyAlias != null &&
                    keyPassword != null
            ) {
                signingConfig =
                    signingConfigs.create("releaseSigning").apply {
                        storeFile = file(keystorePath)
                        storePassword = keystorePassword
                        this.keyAlias = keyAlias
                        this.keyPassword = keyPassword
                    }
            }
        }
    }
    lint { abortOnError = true }
    sourceSets {
        getByName("test").java.srcDir("src/sharedTest/java")
        getByName("androidTest").java.srcDir("src/sharedTest/java")
    }
    testOptions.unitTests.isIncludeAndroidResources = true
    testOptions.unitTests.all {
        it.maxHeapSize = "768m"
        it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3:1.4.0-alpha18")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
