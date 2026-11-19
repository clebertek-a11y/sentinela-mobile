package br.gov.rs.sapucaiadosul.sentinela

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * Campos complementares do BA guardados na mesma base local da ocorrencia.
 * O schema e aditivo, preservando os registros do piloto anterior.
 */
data class BAServidor(val nome: String, val matricula: String)

data class BAExtras(
    val solicitanteCpf: String = "",
    val tipoSolicitante: String = "",
    val tipoAtendimento: String = "",
    val tiposOcorrencia: List<String> = emptyList(),
    val outroTipo: String = "",
    val responsavelTelefone: String = "",
    val responsavelVinculo: String = "",
    val servidores: List<BAServidor> = emptyList(),
    val aceites: List<String> = emptyList()
) {
    fun toJSON(): JSONObject = JSONObject().apply {
        put("solicitante_cpf",solicitanteCpf)
        put("tipo_solicitante",tipoSolicitante)
        put("tipo_atendimento",tipoAtendimento)
        put("tipos_ocorrencia",JSONArray().apply { tiposOcorrencia.forEach { put(it) } })
        put("outro_tipo",outroTipo)
        put("responsavel_telefone",responsavelTelefone)
        put("responsavel_vinculo",responsavelVinculo)
        put("servidores",JSONArray().apply {
            servidores.forEach { put(JSONObject().put("nome",it.nome).put("matricula",it.matricula)) }
        })
        put("aceites",JSONArray().apply { aceites.distinct().forEach { put(it) } })
    }

    companion object {
        fun fromJSON(json: JSONObject?): BAExtras {
            if(json == null) return BAExtras()
            fun values(key: String) = json.optJSONArray(key)?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf { it.isNotBlank() } }
            } ?: emptyList()
            val people = json.optJSONArray("servidores")
            val staff = if (people == null) emptyList() else (0 until people.length()).mapNotNull { i ->
                people.optJSONObject(i)?.let { BAServidor(it.optString("nome"),it.optString("matricula")) }
            }
            return BAExtras(
                json.optString("solicitante_cpf"),json.optString("tipo_solicitante"),
                json.optString("tipo_atendimento"),values("tipos_ocorrencia"),json.optString("outro_tipo"),
                json.optString("responsavel_telefone"),json.optString("responsavel_vinculo"),
                staff,values("aceites"))
        }
    }
}

object TipoOcorrenciaCatalogo {
    val grupos: Map<String,List<String>> = linkedMapOf(
        "Ameaça/Indício" to listOf("Alagamento","Enchente","Inundação","Queda de Vegetal",
            "Desabamento Imóvel","Desabamento Marquise","Movimento de Massa",
            "Desprendimento de Reboco","Desabamento de Muro","Imóvel com Rachadura",
            "Danos em Residência - Evento Clim. Adverso","Danos em Veículo - Evento Clim. Adverso"),
        "Desabamento/Deslizamento" to listOf("Desabamento de Imóvel","Desabamento de Muro",
            "Desabamento de Marquise","Desabamento de Reboco","Deslizamento de Encosta",
            "Deslizamento de Barreira"),
        "Incêndio" to listOf("Incêndio em Área Rural","Incêndios em Parque/APP",
            "Incêndio - Urbano Residencial","Incêndio - Urbano Industrial"),
        "Outros" to listOf("Queda Vegetal Área Pública","Queda Vegetal Área Particular",
            "Poda de Vegetal","Supressão Vegetal","Alagamento","Enchente","Inundação",
            "Atendimento/Primeiros Socorros","Construção Irregular","Ocupação Irregular",
            "Captura de Abelha","Prevenção/Vistoria a Eventos","Infiltração",
            "Vegetal com Fiação Energizada","Apoio a Secretarias","Apoio a Eventos Municipais")
    )
}

class BAExtrasStore(private val store: LocalStore) {
    private val db get() = store.writableDatabase

    private fun ready() {
        db.execSQL("""CREATE TABLE IF NOT EXISTS ba_extras(
            incident_id TEXT PRIMARY KEY, payload TEXT NOT NULL DEFAULT '{}',
            updated_at TEXT NOT NULL DEFAULT ''
        )""")
    }

    @Synchronized fun get(id: String): BAExtras {
        ready()
        return db.rawQuery("SELECT payload FROM ba_extras WHERE incident_id=?", arrayOf(id)).use { c ->
            if(c.moveToFirst()) try { BAExtras.fromJSON(JSONObject(c.getString(0))) }
            catch (_:Exception) { BAExtras() } else BAExtras()
        }
    }

    /** Salvamento atomicamente no banco local; markPending nao deve ser usado no download. */
    @Synchronized fun save(id: String, value: BAExtras, markPending: Boolean = true) {
        ready()
        val cv=ContentValues().apply {
            put("incident_id",id);put("payload",value.toJSON().toString())
            put("updated_at",LocalStore.now())
        }
        val database=db
        database.beginTransaction()
        try {
            database.insertWithOnConflict("ba_extras",null,cv,SQLiteDatabase.CONFLICT_REPLACE)
            if(markPending) {
                database.execSQL(
                    "UPDATE incidents SET sync_state='PENDENTE',updated_at=? WHERE id=?",
                    arrayOf(LocalStore.now(),id)
                )
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }
}
