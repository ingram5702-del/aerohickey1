plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.appwizard.airhockey"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.appwizard.airhockey"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    sourceSets {
        getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/firebaseAssets"))
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.concurrent.futures)
    implementation(libs.guava.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.google.material)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.google.mlkit.barcode.scanning)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)

    debugImplementation(libs.androidx.compose.ui.tooling)
}

val copyGoogleServicesToAssets by tasks.registering(Copy::class) {
    val configFile = layout.projectDirectory.file("google-services.json")
    from(configFile)
    into(layout.buildDirectory.dir("generated/firebaseAssets"))
    onlyIf { configFile.asFile.exists() }
}

tasks.matching { task ->
    task.name.startsWith("merge") && task.name.endsWith("Assets")
}.configureEach {
    dependsOn(copyGoogleServicesToAssets)
}

tasks.matching { task ->
    (task.name.startsWith("generate") && task.name.endsWith("LintReportModel")) ||
        task.name.startsWith("lintAnalyze")
}.configureEach {
    dependsOn(copyGoogleServicesToAssets)
}
