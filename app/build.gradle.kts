import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("com.google.protobuf")
}

// Bump both for every release. versionName ends up in the APK file name.
val appVersionCode = 42
val appVersionName = "2.18.0"

// Release signing credentials live in keystore.properties (git-ignored).
// Without that file, release builds are produced unsigned.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// APKs come out as RFSentinel-v<versionName>-<buildType>.apk
base.archivesName.set("RFSentinel-v$appVersionName")

android {
    namespace = "com.rfsentinel.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.rfsentinel.app"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Installs next to the release app (demo data, screenshots) without touching it.
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

// Generates WazeProto (the Waze direct backend's wire format) from src/main/proto/waze.proto.
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.35.0" }
    generateProtoTasks {
        all().forEach { task -> task.builtins { maybeCreate("java").option("lite") } }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    // Pull the list down to check Waze now.
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.activity:activity-ktx:1.13.0")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("com.google.code.gson:gson:2.14.0")
    // Waze direct backend: lite protobuf runtime for the app protocol.
    implementation("com.google.protobuf:protobuf-javalite:4.35.0")

    // OpenStreetMap map view (Apache-2.0, no API key). Tiles are the only network use.
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Android Auto (Car App Library, template-based UI; "projected" = phone-driven Android Auto)
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
    // Android Auto media-style screen (MediaBrowserServiceCompat).
    implementation("androidx.media:media:1.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.car.app:app-testing:1.7.0")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
}
