#!/usr/bin/env python3
"""Aplica modo SIGADE Central APENAS ao source ZIP extraído durante build de HOMOLOGAÇÃO.
Não altera sentinela_source_payload.zip nem as assinaturas do APK já publicado.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/src/main/java/br/gov/rs/sapucaiadosul/sentinela"
SOURCE.mkdir(parents=True, exist_ok=True)

for name in ("CentralSync.kt", "CentralActivity.kt"):
    path = SOURCE / name
    if path.exists():
        raise SystemExit(f"Arquivo {name} já existe; revisão manual necessária.")
    path.write_bytes((ROOT / "patches/central-sync" / name).read_bytes())

main = SOURCE / "MainActivity.kt"
text = main.read_text(encoding="utf-8")
anchor = 'if(has(Rights.CREATE)) quicks.add("+ Registrar ocorrência" to {newIncident()})'
if text.count(anchor) != 1:
    raise SystemExit("Layout Android não corresponde à versão homologada; não aplicar patch.")
text = text.replace(anchor,
    'quicks.add("SIGADE CENTRAL · compartilhado" to {startActivity(Intent(this@MainActivity, CentralActivity::class.java))})\n'
    '        ' + anchor)
# Tornar explícita a separação entre o módulo antigo e o novo sem apagar registros.
text = text.replace('Registro local no SIGOPE', 'Registro LOCAL no SIGOPE (não compartilhado)')
text = text.replace('O aplicativo não usa o banco SIGOPE ou BA da Lovable.',
                    'O modo LOCAL é independente. Para compartilhar ocorrências utilize SIGADE CENTRAL.')
main.write_text(text, encoding="utf-8")

manifest = ROOT / "app/src/main/AndroidManifest.xml"
m = manifest.read_text(encoding="utf-8")
if '<uses-permission android:name="android.permission.INTERNET"' not in m:
    m = m.replace("<application", '<uses-permission android:name="android.permission.INTERNET"/>\n    <application', 1)
if m.count("</application>") != 1:
    raise SystemExit("AndroidManifest inesperado.")
m = m.replace("</application>",
              '    <activity android:name=".CentralActivity" android:exported="false"/>\n    </application>')
manifest.write_text(m, encoding="utf-8")

build = ROOT / "app/build.gradle.kts"
g = build.read_text(encoding="utf-8")
for needle in ('applicationId = "br.gov.rs.sapucaiadosul.sentinela.atualizavel"',
               "versionCode = 11", 'versionName = "0.5.0"'):
    if g.count(needle) != 1:
        raise SystemExit(f"Versão-base Android divergente: {needle}")
g = g.replace('applicationId = "br.gov.rs.sapucaiadosul.sentinela.atualizavel"',
              'applicationId = "br.gov.rs.sapucaiadosul.sentinela.atualizavel.homologacao"')
g = g.replace("versionCode = 11", "versionCode = 12")
g = g.replace('versionName = "0.5.0"', 'versionName = "0.6.0-homologacao"')
build.write_text(g, encoding="utf-8")
print("SIGADE Central integrado à compilação de homologação; build oficial preservado.")
