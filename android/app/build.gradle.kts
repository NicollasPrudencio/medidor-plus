import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Chave fixa de assinatura: todas as versões precisam da mesma chave para uma atualizar a outra.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Servidor e token embutidos no app (o usuário instala e já funciona). Arquivo local, fora do git.
val publishProps = Properties().apply {
    val f = rootProject.file("publish.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun publishProp(key: String): String = publishProps.getProperty(key, "").trim()
val serverUrl = publishProp("url").trimEnd('/')
val downloadUrl = if (serverUrl.isNotEmpty() && publishProp("downloadKey").isNotEmpty())
    "$serverUrl/baixar/${publishProp("downloadKey")}" else ""

android {
    namespace = "com.consumo.tuya"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.consumo.tuya"
        minSdk = 26
        targetSdk = 35
        // Aumente versionCode a cada publicação (publicar.ps1 lê estes valores)
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "DEFAULT_URL", "\"$serverUrl\"")
        buildConfigField("String", "DOWNLOAD_URL", "\"$downloadUrl\"")
    }

    if (keystoreProps.isNotEmpty()) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // mesma chave no debug: o app instalado pelo Android Studio aceita as atualizações
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    systemProperty("exportDir", layout.buildDirectory.dir("export-test").get().asFile.absolutePath)
}
