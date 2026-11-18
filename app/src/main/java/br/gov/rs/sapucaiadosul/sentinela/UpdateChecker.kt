package br.gov.rs.sapucaiadosul.sentinela

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Catálogo público sem credenciais. Verifica somente o pacote/linha de atualização do app.
 * Toda instalação de APK é confirmada pelo sistema Android.
 */
object UpdateChecker {
    data class Release(
        val version: String,
        val versionCode: Long,
        val apkUrl: String,
        val notes: String,
        val required: Boolean
    )

    private const val CATALOG_URL =
        "https://raw.githubusercontent.com/clebertek-a11y/sentinela-mobile/main/updates/android-estavel.json"
    private const val RELEASE_PREFIX =
        "https://github.com/clebertek-a11y/sentinela-mobile/releases/download/"

    fun installedVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "desconhecida"

    @Suppress("DEPRECATION")
    private fun installedVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
               else info.versionCode.toLong()
    }

    fun findNewerRelease(context: Context): Release? {
        val connection = URL(CATALOG_URL).openConnection() as HttpsURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.setRequestProperty("Cache-Control", "no-cache")
        connection.setRequestProperty("User-Agent", "SENTINELA-Android-Update-Checker")
        try {
            if (connection.responseCode != 200) return null
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val data = JSONObject(body)
            // Não oferecer APKs destinados a outro aplicativo/canal.
            if (data.optString("applicationId") != context.packageName) return null
            val versionCode = data.optLong("versionCode", 0)
            if (versionCode <= installedVersionCode(context)) return null
            val downloadUrl = data.optString("apkUrl")
            if (!downloadUrl.startsWith(RELEASE_PREFIX) ||
                !downloadUrl.endsWith(".apk", ignoreCase = true)) return null
            return Release(
                data.optString("versionName", "nova"),
                versionCode,
                downloadUrl,
                data.optString("notes", "Melhorias e correções do SENTINELA."),
                data.optBoolean("required", false)
            )
        } finally { connection.disconnect() }
    }
}
