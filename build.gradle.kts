import java.net.URI

plugins {
    id("com.android.application") version "9.5.1"
}

// Redlib's Android binaries, packaged as native libraries: Android only lets an app execute files
// from its native library directory.
val redlibLibs = layout.buildDirectory.dir("redlib")
val fetchRedlib = tasks.register("fetchRedlib") {
    val abis = mapOf("arm64-v8a" to "aarch64-linux-android", "armeabi-v7a" to "armv7-linux-androideabi")
    val dir = redlibLibs
    // -PredlibDir=<folder with redlib-<target> files> builds with those instead of the latest release.
    val local = providers.gradleProperty("redlibDir")
    inputs.property("local", local.orElse(""))
    outputs.dir(dir)
    doLast {
        abis.forEach { (abi, target) ->
            val lib = dir.get().file("$abi/libredlib.so").asFile
            lib.parentFile.mkdirs()
            val source = local.orNull?.let { File(it, "redlib-$target").toURI() } ?: URI("https://github.com/Nachtalb/redlib/releases/latest/download/redlib-$target")
            source.toURL().openStream().use { input -> lib.outputStream().use { input.copyTo(it) } }
        }
    }
}

android {
    namespace = "io.nachtalb.redlib"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.nachtalb.redlib"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        // Committed, so every build installs over the last one.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    sourceSets["main"].jniLibs.directories.add("build/redlib")
    // Extracted at install, so the binary exists as a file to execute.
    packaging.jniLibs.useLegacyPackaging = true
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.named("preBuild") { dependsOn(fetchRedlib) }
