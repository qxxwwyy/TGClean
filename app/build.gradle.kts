plugins {
    alias(libs.plugins.agp.app)
}

android {
    namespace = "com.tgclean"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.tgclean"
        minSdk = 28
        targetSdk = 36
        versionCode = 20
        versionName = "2.0.2"
    }

    signingConfigs {
        create("release") {
            // 私钥与密码一律由环境变量注入（CI 来自 GitHub Secrets），仓库内不保存任何密钥材料
            val ksPass = System.getenv("TGCLEAN_KS_PASS")
            if (ksPass != null) {
                storeFile = file(System.getenv("TGCLEAN_KS_FILE") ?: "release.keystore")
                storePassword = ksPass
                keyAlias = "tgclean"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            signingConfig = if (System.getenv("TGCLEAN_KS_PASS") != null) {
                signingConfigs["release"]
            } else {
                signingConfigs["debug"]
            }
        }
        debug {
            signingConfig = if (System.getenv("TGCLEAN_KS_PASS") != null) {
                signingConfigs["release"]
            } else {
                signingConfigs["debug"]
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    compileOnly(libs.androidx.annotation)
    implementation(libs.libxposed.service)
    implementation(libs.material)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
}
