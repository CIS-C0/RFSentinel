import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("com.google.protobuf")
}

// Bump both for every release. versionName ends up in the APK file name.
val appVersionCode = 46
val appVersionName = "2.21.1"

// Release signing credentials live in keystore.properties (git-ignored).
// Without that file, release builds are produced unsigned.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Mapbox public token (Settings > Map > Map provider) from local.properties (git-ignored), never the repo.
// Without it the Mapbox choice is unavailable and the map stays on OpenFreeMap.
val mapboxToken: String = Properties().run {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
    getProperty("MAPBOX_ACCESS_TOKEN", "").trim()
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
        buildConfigField("String", "MAPBOX_TOKEN", "\"$mapboxToken\"")
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
            // The map engines' native libraries are ~15 MB per CPU type: 64-bit ARM phones only (every
            // phone of the last several years), not 32-bit ARM or the x86 emulator builds, which debug builds keep.
            ndk { abiFilters += listOf("arm64-v8a") }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Native libraries stored compressed: a much smaller APK to download (unpacked once at install).
    packaging { jniLibs { useLegacyPackaging = true } }

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
    // The map engine (3D driving view): vector maps with a real 3D camera. The Vulkan + OpenGL build picks Vulkan only
    // where the phone has it and OpenGL ES everywhere else (the plain build is Vulkan-only and crashes the app on phones
    // without Vulkan, e.g. Android 9 phones with a PowerVR GE8320).
    implementation("org.maplibre.gl:android-sdk-vulkan-opengl:13.6.1")
    // Optional Mapbox map (Settings > Map > Map provider); needs MAPBOX_ACCESS_TOKEN in local.properties.
    implementation("com.mapbox.maps:android-ndk27:11.32.0")

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
