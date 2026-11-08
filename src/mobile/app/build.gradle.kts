plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val separationResearch = providers.gradleProperty("separationResearch").orNull == "true"

// Gradle's Windows test worker cannot load classes from this host's Cyrillic path.
System.getenv("NEUROSCRIBE_BUILD_DIR")?.let { layout.buildDirectory.set(file(it)) }

android {
    if (providers.gradleProperty("nativeEvidenceResearch").orNull == "true")
        sourceSets.getByName("androidTest").kotlin.srcDir("src/nativeEvidenceResearch/java")
    if (separationResearch) sourceSets.getByName("androidTest").kotlin.srcDir("src/separationResearch/java")
    if (providers.gradleProperty("diarizationResearch").orNull == "true")
        sourceSets.getByName("test").kotlin.srcDir("src/diarizationResearch/java")
    if (providers.gradleProperty("cleanrefsResearch").orNull == "true")
        sourceSets.getByName("androidTest").kotlin.srcDir("src/cleanrefsResearch/java")
    namespace = "ru.neuroscribe"
    compileSdk = 36
    defaultConfig {
        applicationId = "ru.neuroscribe"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.7.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources { noCompress += "onnx" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(files(if(providers.gradleProperty("nativeAverageResearch").orNull=="true")
        rootProject.file(".work/native-average-experiment.aar") else file("libs/sherpa-onnx-neuro-evidence.aar")))
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    // Explicit research build only; regular instrumentation keeps exactly the production JNI libraries.
    if (separationResearch) androidTestImplementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.08.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Research tests read explicitly supplied external PCM/vector caches and phases.
// Never reuse a previous "windows" result for "refine" merely because bytecode
// stayed unchanged. Ordinary unit tests keep Gradle's normal incremental behavior.
if (providers.gradleProperty("diarizationResearch").orNull == "true") {
    tasks.withType<Test>().configureEach { outputs.upToDateWhen { false } }
}
