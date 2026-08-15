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
        versionCode = 18
        versionName = "2.0.0"
    }

    signingConfigs {
        create("release") {
            // 使用仓库中的固定keystore，确保每次构建签名一致可覆盖安装。
            // 密码优先取环境变量（CI 注入）；本地构建回退到默认值。
            val ksPass = System.getenv("TGCLEAN_KS_PASS") ?: "tgclean2026"
            storeFile = file("release.keystore")
            storePassword = ksPass
            keyAlias = "tgclean"
            keyPassword = ksPass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            signingConfig = signingConfigs["release"]
        }
        debug {
            signingConfig = signingConfigs["release"]
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
