plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

abstract class BundleChangelog : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val changelog: RegularFileProperty

    @get:OutputDirectory
    abstract val assets: DirectoryProperty

    @TaskAction
    fun copyChangelog() {
        changelog.get().asFile.copyTo(assets.file("changelog.md").get().asFile, overwrite = true)
    }
}

androidComponents.onVariants { variant ->
    val bundleChangelog = tasks.register<BundleChangelog>("bundle${variant.name.replaceFirstChar { it.uppercaseChar() }}Changelog") {
        changelog.set(rootProject.layout.projectDirectory.file("changelog.md"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(bundleChangelog, BundleChangelog::assets)
}

android {
    namespace = "com.darknight.miuisettingmod"
    compileSdk {
        // miuix 0.9.5 的 AAR 要求 compileSdk 至少为 Android 37.2。
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.darknight.miuisettingmod"
        minSdk = 35
        targetSdk = 37
        versionCode = 7
        versionName = "1.2.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val storePwd = System.getenv("KEYSTORE_PASSWORD")
            storeFile = file("../release.keystore")
            storePassword = storePwd
            // CI 里未配置的 secret 会以空字符串注入，需按空值处理，否则默认值不生效。
            keyAlias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "release"
            keyPassword = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: storePwd
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }
    compileOptions {
        // miuix-nav 的 inline 路由 API 使用 JVM 21 字节码。
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            merges += listOf("META-INF/xposed/*")
        }
        dex {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":hook"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.miuix.core)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.squircle)
    implementation(libs.miuix.navigation)
    // OS4 玻璃组件（HyperOS 4 材质）
    implementation(libs.miuix.material)
    implementation(libs.miuix.glass)
    implementation(libs.miuix.glass.icons)
    implementation(libs.material.icons)
    implementation(libs.commonmark)
}

