import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Generated SQLDelight schema lives in its own module so its public generated types stay out of the
// :shared ObjC framework header (SQLDelight cannot generate internal declarations).
plugins {
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    android {
        namespace = "app.wishlist.localdb"
        compileSdk = 36
        minSdk = 26
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework { baseName = "LocalDb" }
    }

    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            api(libs.sqldelight.runtime)
        }
    }
}

sqldelight {
    databases {
        create("WishlistDatabase") { packageName.set("app.wishlist.shared.data.local") }
    }
}
