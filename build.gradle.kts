import java.net.HttpURLConnection
import java.net.URI

plugins {
    id("com.android.application") version "9.5.1"
}

/** The app's own version, bumped by hand when the app changes. A new value publishes a release. */
val appVersion = "0.2.1"

/** The tag of the Redlib fork's newest release, read from where `releases/latest` redirects to. */
abstract class LatestRedlib : ValueSource<String, ValueSourceParameters.None> {
    override fun obtain(): String {
        val connection = URI("https://github.com/Nachtalb/redlib/releases/latest").toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        return connection.getHeaderField("Location").substringAfterLast("/tag/")
    }
}

// -PredlibDir=<folder with redlib-<target> files> builds with those instead of a release.
val redlibDir = providers.gradleProperty("redlibDir")
// The CI passes -PredlibVersion so the published version.json names the release it built with.
val redlibVersion = if (redlibDir.isPresent) "local" else providers.gradleProperty("redlibVersion").orElse(providers.of(LatestRedlib::class) {}).get()

// Redlib's Android binaries, packaged as native libraries: Android only lets an app execute files
// from its native library directory.
val redlibLibs = layout.buildDirectory.dir("redlib")
val fetchRedlib = tasks.register("fetchRedlib") {
    val abis = mapOf("arm64-v8a" to "aarch64-linux-android", "armeabi-v7a" to "armv7-linux-androideabi")
    val dir = redlibLibs
    val local = redlibDir
    val version = redlibVersion
    inputs.property("local", local.orElse(""))
    inputs.property("version", version)
    outputs.dir(dir)
    doLast {
        abis.forEach { (abi, target) ->
            val lib = dir.get().file("$abi/libredlib.so").asFile
            lib.parentFile.mkdirs()
            val source = local.orNull?.let { File(it, "redlib-$target").toURI() } ?: URI("https://github.com/Nachtalb/redlib/releases/download/$version/redlib-$target")
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
        // Raised by the CI with every published build, so an installed app knows a newer one.
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
        versionName = "$appVersion-$redlibVersion"
    }

    // The release key stays out of the repo: the CI gets it from the repo's secrets.
    val keystore = System.getenv("REDLIB_KEYSTORE")
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("REDLIB_KEYSTORE_PASSWORD")
            keyAlias = "redlib"
            keyPassword = storePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // Drops what the AndroidX libraries bring along that the app never uses.
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Without the key a build is signed with this machine's debug key and can't update a published one.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    sourceSets["main"].jniLibs.directories.add("build/redlib")
    // Extracted at install, so the binary exists as a file to execute.
    packaging.jniLibs.useLegacyPackaging = true
}

dependencies {
    // Android's own refresh indicator, which the platform has no widget for.
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    testImplementation("junit:junit:4.13.2")
}

tasks.named("preBuild") { dependsOn(fetchRedlib) }
