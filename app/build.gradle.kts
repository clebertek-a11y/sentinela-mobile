plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.gov.rs.sapucaiadosul.sentinela"
    compileSdk = 35
    defaultConfig {
        applicationId = "br.gov.rs.sapucaiadosul.sentinela.atualizavel"
        minSdk = 29
        targetSdk = 35
        versionCode = 16
        versionName = "0.7.3"
    }
    // Chave institucional permanente: apenas variáveis de ambiente do CI.
    // Nunca colocar chaves de assinatura nem senhas no código/repositório.
    signingConfigs {
        create("sentinela") {
            val keyFilePath = System.getenv("SENTINELA_KEYSTORE_PATH")
            if (!keyFilePath.isNullOrBlank()) {
                storeFile = file(keyFilePath)
                storePassword = System.getenv("SENTINELA_KEYSTORE_PASSWORD")
                keyAlias = "sentinela-mobile"
                keyPassword = System.getenv("SENTINELA_KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".hub073"
            versionNameSuffix = "-piloto"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sentinela")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = false }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}
