plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "io.mo.glassmic"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.mo.glassmic"
        minSdk = 29
        targetSdk = 35
        versionCode = 17
        // api100/legacy：去掉 META-INF/xposed/java_init.list，module.prop=82，供 LSPosed 1.9.2 经典入口加载
        versionName = "1.3.4-api100"
        resourceConfigurations += listOf("zh-rCN", "en")
        // 与 :xposed / Sherpa TTS native 对齐，只打 arm64
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    androidResources {
        // Piper .onnx / .fst 不能被 aapt 压缩，否则 sherpa-onnx 无法 mmap
        noCompress += listOf("onnx", "fst")
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            // 正式包但不混淆/压缩资源，便于对照与二次修改
            isMinifyEnabled = false
            isShrinkResources = false
            // 仓库未附带正式 keystore；用 debug 签名保证 APK 可直接安装。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES"
        )
        // shadowhook 的 .so 会同时从 :xposed 子模块 与 shadowhook AAR 两条路径进来；
        // 两者内容一致，让 merge 任务取其一即可（不能用 excludes，那样最终 apk 里就没有了）
        jniLibs {
            pickFirsts += setOf(
                "**/libshadowhook.so",
                "**/libshadowhook_nothing.so"
            )
        }
    }
}

kotlin { jvmToolchain(17) }

// ============== Proto Lite ==============
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                // java lite
                create("java") { option("lite") }
                // kotlin lite (官方支持，依赖 protobuf-kotlin-lite)
                create("kotlin") { option("lite") }
            }
        }
    }
}

dependencies {
    implementation(project(":core"))
    // xposed 模块作为 library 打入同一个 APK；本构建为 api100/legacy，仅走 assets/xposed_init
    implementation(project(":xposed"))
    compileOnly(libs.libxposed.api)
    compileOnly(libs.legacy.xposed.api)

    // 内置离线 TTS：Sherpa-ONNX（Piper 中文），本地 AAR 见 app/libs/sherpa-onnx.aar
    implementation(files("libs/sherpa-onnx.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.documentfile)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.navigation)
    debugImplementation(libs.compose.ui.tooling)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore + Proto
    implementation(libs.datastore)
    implementation(libs.protobuf.javalite)
    implementation(libs.protobuf.kotlin.lite)

    // 协程
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
}
