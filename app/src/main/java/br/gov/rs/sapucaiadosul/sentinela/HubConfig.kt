package br.gov.rs.sapucaiadosul.sentinela

import android.content.Context

/**
 * A URL publica e configurada apenas pela equipe de TI no pacote instalado.
 * Nao requer Tailscale nem introducao manual de URL pelo agente.
 * Nao funciona ate o administrador habilitar o Funnel na porta 10000.
 */
object HubConfig {
    const val URL = "https://servidor02.tail2ad9e6.ts.net:10000"
    fun ensure(context: Context) {
        val prefs = context.getSharedPreferences("sync_settings", Context.MODE_PRIVATE)
        if (prefs.getString("url","") != URL) {
            prefs.edit().putString("url",URL).apply()
        }
    }
}
