import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// DEX compilation task: compile hidden API clipboard listener to DEX and package as listener.zip
val compileDex by tasks.registering {
    group = "build"
    description = "Compile DEX for hidden API clipboard listener"

    val dexSrcDir = file("${rootProject.projectDir}/dex_src")
    val dexBuildDir = layout.buildDirectory.dir("dex_build")
    val assetsDir = file("${project.projectDir}/src/main/assets")
    val outputZip = file("${assetsDir}/listener.zip")

    inputs.dir(dexSrcDir)
    outputs.file(outputZip)

    doLast {
        val androidSdkDir = android.sdkDirectory
        // Find the latest platform
        val platformsDir = file("${androidSdkDir}/platforms")
        val platformDir = platformsDir.listFiles()?.lastOrNull { it.isDirectory && it.name.startsWith("android-") }
            ?: throw GradleException("No Android platform found in ${platformsDir}")
        val androidJar = file("${platformDir}/android.jar")
        if (!androidJar.exists()) throw GradleException("android.jar not found at ${androidJar}")

        // Find d8 tool
        val buildToolsDir = file("${androidSdkDir}/build-tools")
        val buildToolsVersionDir = buildToolsDir.listFiles()?.lastOrNull { it.isDirectory }
            ?: throw GradleException("No build-tools found in ${buildToolsDir}")
        val d8tool = file("${buildToolsVersionDir}/d8")
        val d8cmd = if (System.getProperty("os.name").lowercase().contains("win")) "${d8tool}.bat" else d8tool.absolutePath

        // Clean and prepare build dir
        val buildDir = dexBuildDir.get().asFile
        buildDir.deleteRecursively()
        buildDir.mkdirs()
        assetsDir.mkdirs()

        // Compile Java files
        val javaFiles = dexSrcDir.walk().filter { it.isFile && it.extension == "java" }.toList()
        logger.lifecycle("Compiling ${javaFiles.size} DEX Java files...")

        val javacExec = "${System.getProperty("java.home")}/bin/javac"
        exec {
            commandLine(
                javacExec,
                "-encoding", "UTF-8",
                "-d", buildDir.absolutePath,
                "-cp", androidJar.absolutePath,
                "-sourcepath", dexSrcDir.absolutePath,
                *javaFiles.map { it.absolutePath }.toTypedArray()
            )
            isIgnoreExitValue = false
        }

        // Convert to DEX
        logger.lifecycle("Converting to DEX...")
        val classFiles = buildDir.walk().filter { it.isFile && it.extension == "class" }.map { it.absolutePath }.toList()
        exec {
            commandLine(
                d8cmd,
                "--lib", androidJar.absolutePath,
                "--output", outputZip.absolutePath,
                *classFiles.toTypedArray()
            )
            isIgnoreExitValue = false
        }

        logger.lifecycle("DEX compiled successfully: ${outputZip.absolutePath}")
    }
}

// Make sure DEX is compiled before merging assets
tasks.named("preBuild") {
    dependsOn(compileDex)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

android {
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("signing/moting")
            storePassword = localProperties.getProperty("SIGNING_STORE_PASSWORD")
            keyAlias = localProperties.getProperty("SIGNING_KEY_ALIAS")
            keyPassword = localProperties.getProperty("SIGNING_KEY_PASSWORD")
        }
    }

    namespace = "com.moting.linkgo"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.moting.linkgo"
        minSdk = 29
        targetSdk = 36
        versionCode = 55
        versionName = "2.0.8_2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "LinkGo_${defaultConfig.versionName}.APK"
        }
    }
}

dependencies {
    compileOnly(project(":hidden-api"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hiddenapibypass)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // LSPosed：api 保持 102（HookEntry 使用 102 新增的 HookBuilder.setId）；service 降到 101
    // （service 102 含 Java 17 record 类，AGP 8 外部库 dexing 无法 Record desugaring；101 无 record 且签名兼容）
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:101.0.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}