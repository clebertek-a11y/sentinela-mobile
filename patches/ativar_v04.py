from pathlib import Path

root = Path(".")
gradle = root / "app/build.gradle.kts"
g = gradle.read_text(encoding="utf-8")
assert 'applicationId = "br.gov.rs.sapucaiadosul.sentinela"' in g, "App ID original não encontrado"
assert 'versionCode = 3' in g, "Versão do pacote original inesperada"
assert 'versionName = "0.3.0"' in g, "Nome da versão original inesperado"
g = g.replace('applicationId = "br.gov.rs.sapucaiadosul.sentinela"',
              'applicationId = "br.gov.rs.sapucaiadosul.sentinela.atualizavel"')
g = g.replace('versionCode = 3','versionCode = 10')
g = g.replace('versionName = "0.3.0"','versionName = "0.4.0"')
old = """    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }"""
new = """    signingConfigs {
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
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sentinela")
        }
    }"""
assert old in g, "Bloco Gradle esperado não encontrado"
gradle.write_text(g.replace(old,new),encoding="utf-8")

manifest = root/"app/src/main/AndroidManifest.xml"
m = manifest.read_text(encoding="utf-8")
assert 'android:label="SENTINELA"' in m
manifest.write_text(m.replace('android:label="SENTINELA"','android:label="SENTINELA ATUALIZAVEL"'),encoding="utf-8")

src = root/"app/src/main/java/br/gov/rs/sapucaiadosul/sentinela/MainActivity.kt"
s = src.read_text(encoding="utf-8")
assert 'if (interactive || last != release.version)' in s
# Exibe o aviso a cada nova abertura enquanto a versão estiver desatualizada.
s=s.replace('if (interactive || last != release.version)','if (true)')
s=s.replace('UpdateChecker.findNewerRelease(this, isTestBuild())',
            'UpdateChecker.findNewerRelease(this, isTestBuild())')
s=s.replace('"SENTINELA • DEFESA CIVIL"','"SENTINELA • DEFESA CIVIL • BETA"')
src.write_text(s,encoding="utf-8")

checker = root/"app/src/main/java/br/gov/rs/sapucaiadosul/sentinela/UpdateChecker.kt"
checker.write_text((root/"patches/UpdateChecker.kt").read_text(encoding="utf-8"),encoding="utf-8")
print("SENTINELA 0.4.0 — versão atualizável, assinatura de release configurável, app separado.")
