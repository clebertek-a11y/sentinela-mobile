package br.gov.rs.sapucaiadosul.sentinela

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Consulta o catálogo público de versões; a instalação do APK exige aceite do Android. */
object UpdateChecker {
    data class Release(val version: String, val apkUrl: String, val notes: String)
    private const val CATALOG_URL =
        "https://raw.githubusercontent.com/clebertek-a11y/sentinela-mobile/main/updates/android-estavel.json"
    private const val RELEASE_PREFIX =
        "https://github.com/clebertek-a11y/sentinela-mobile/releases/download/"

    fun installedVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "desconhecida"

    @Suppress("DEPRECATION")
    private fun installedCode(context: Context): Long {
        val p = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) p.longVersionCode else p.versionCode.toLong()
    }

    fun findNewerRelease(context: Context, allowPrerelease: Boolean): Release? {
        val connection = URL(CATALOG_URL).openConnection() as HttpsURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.setRequestProperty("Cache-Control", "no-cache")
        connection.setRequestProperty("User-Agent", "SentinelaMobileUpdateChecker")
        try {
            if (connection.responseCode != 200) return null
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val data = JSONObject(body)
            if (data.optString("applicationId") != context.packageName) return null
            if (data.optLong("versionCode", 0) <= installedCode(context)) return null
            val link = data.optString("apkUrl", "")
            if (!link.startsWith(RELEASE_PREFIX) || !link.endsWith(".apk", true)) return null
            return Release(data.optString("versionName", "nova"), link,
                data.optString("notes", "Atualização disponível para o SENTINELA."))
        } finally {
            connection.disconnect()
        }
    }
}
