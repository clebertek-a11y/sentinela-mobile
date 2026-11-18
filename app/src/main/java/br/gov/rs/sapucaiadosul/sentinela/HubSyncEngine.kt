package br.gov.rs.sapucaiadosul.sentinela

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sincronizacao incremental do SIGOPE com Sentinela HUB v1.0.
 * Nunca utiliza HTTP nao cifrado e nunca descarta um conflito silenciosamente.
 * Credencial armazenada no Android Keystore pelo SecureSecrets.
 */
class HubSyncEngine(private val context: Context) {
    data class Result(val sent: Int, val received: Int, val attachments: Int, val conflicts: Int)
    private val prefs = context.getSharedPreferences("sync_settings", Context.MODE_PRIVATE)
    private val store = LocalStore(context)
    private val db = store.writableDatabase
    private val base = prefs.getString("url", "")!!.trim().trimEnd('/')
    private val user = prefs.getString("hub_user", "")!!.trim()
    private val password = SecureSecrets.read(context)
    private class HttpError(val code: Int, body: String): Exception("Servidor HTTP " + code + ": " + body.take(200))
    private companion object { val lock = Any() }

    init {
        db.execSQL("""CREATE TABLE IF NOT EXISTS hub_sync_meta(
            incident_id TEXT PRIMARY KEY, server_version INTEGER NOT NULL DEFAULT 0,
            payload_hash TEXT NOT NULL DEFAULT '', mutation_id TEXT NOT NULL DEFAULT '',
            conflict_json TEXT NOT NULL DEFAULT ''
        )""")
    }

    fun run(): Result = synchronized(lock) {
        require(base.startsWith("https://") && !base.contains("@") && URL(base).path.trim('/') == "") {
            "Configure apenas o endereco HTTPS raiz do HUB em Configuracoes"
        }
        require(user.isNotBlank() && password.isNotBlank()) {
            "Informe usuario e senha do HUB em Configuracoes"
        }
        val token = request("POST", "/api/v1/auth/login",
            JSONObject().put("username", user).put("password", password), null)
            .getString("access_token")
        var sent = 0
        var received = 0
        var attachments = 0
        var conflicts = 0

        // Enviar primeiro: preserva versoes e identifica edicoes concorrentes.
        for (item in store.incidents().filter { it.syncState == "PENDENTE" }) {
            val payload = encode(item)
            val fingerprint = sha256(payload.toString().toByteArray(Charsets.UTF_8))
            val old = meta(item.id)
            val mutation = if (old.second == fingerprint && old.third.isNotBlank()) old.third
                           else UUID.randomUUID().toString()
            writeMeta(item.id, old.first, fingerprint, mutation, old.fourth)
            val change = JSONObject().put("id", item.id).put("collection", "ocorrencias")
                .put("base_version", old.first).put("mutation_id", mutation)
                .put("deleted", false).put("data", payload)
            val response = try {
                request("POST", "/api/v1/sync/push",
                    JSONObject().put("changes", JSONArray().put(change)), token)
            } catch (e: HttpError) {
                if (e.code == 409) {
                    // Mantem a versao local intacta. Exige conciliacao supervisionada.
                    db.execSQL("UPDATE incidents SET sync_state='CONFLITO' WHERE id=?", arrayOf(item.id))
                    db.execSQL("UPDATE hub_sync_meta SET conflict_json=? WHERE incident_id=?",
                        arrayOf(e.message ?: "Conflito no HUB", item.id))
                    conflicts++
                    continue
                }
                throw e
            }
            val ack = response.getJSONArray("accepted").getJSONObject(0)
            val version = ack.getLong("version")
            writeMeta(item.id, version, fingerprint, mutation, "")
            attachments += sendFiles(item, token)
            if (store.incident(item.id)?.let { sha256(encode(it).toString().toByteArray(Charsets.UTF_8)) } == fingerprint) {
                db.execSQL("UPDATE incidents SET sync_state='SINCRONIZADO' WHERE id=? AND sync_state='PENDENTE'", arrayOf(item.id))
            }
            sent++
        }

        var cursor = prefs.getLong("hub_cursor", 0L)
        var pages = 0
        while (pages++ < 1000) {
            val response = request("GET", "/api/v1/sync/pull?since=" + cursor + "&limit=200", null, token)
            val changes = response.getJSONArray("changes")
            db.beginTransaction()
            try {
                for (i in 0 until changes.length()) {
                    val remote = changes.getJSONObject(i)
                    if (remote.optString("collection") != "ocorrencias") continue
                    val id = remote.getString("id")
                    val version = remote.getLong("version")
                    val local = store.incident(id)
                    val known = meta(id).first
                    if (version <= known) continue
                    if (remote.optBoolean("deleted",false)) {
                        // Nao excluir registros locais automaticamente.
                        if (local != null) {
                            db.execSQL("UPDATE incidents SET sync_state='CONFLITO' WHERE id=?", arrayOf(id))
                            writeMeta(id, known, "", "", remote.toString())
                            conflicts++
                        }
                        continue
                    }
                    if (local != null && local.syncState != "SINCRONIZADO") {
                        db.execSQL("UPDATE incidents SET sync_state='CONFLITO' WHERE id=?", arrayOf(id))
                        writeMeta(id, known, "", "", remote.toString())
                        conflicts++
                        continue
                    }
                    val data = remote.getJSONObject("data")
                    importRecord(id, data, local != null)
                    writeMeta(id, version, "", "", "")
                    received++
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            // Armazenar cursor APENAS depois da transacao local concluida.
            cursor = response.getLong("cursor")
            if (!prefs.edit().putLong("hub_cursor", cursor).commit()) error("Falha ao salvar cursor")
            if (!response.optBoolean("has_more", false)) break
        }
        Result(sent, received, attachments, conflicts)
    }

    private data class Revision(val first: Long, val second: String, val third: String, val fourth: String)
    private fun meta(id: String): Revision =
        db.rawQuery("SELECT server_version,payload_hash,mutation_id,conflict_json FROM hub_sync_meta WHERE incident_id=?",
            arrayOf(id)).use { c ->
                if (c.moveToFirst()) Revision(c.getLong(0),c.getString(1),c.getString(2),c.getString(3))
                else Revision(0L,"","","")
            }

    private fun writeMeta(id: String, version: Long, hash: String, mutation: String, conflict: String) {
        val cv = ContentValues().apply {
            put("incident_id",id);put("server_version",version);put("payload_hash",hash)
            put("mutation_id",mutation);put("conflict_json",conflict)
        }
        db.insertWithOnConflict("hub_sync_meta",null,cv,SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun encode(i: Incident): JSONObject {
        val r = store.report(i.id)
        val report = JSONObject().put("historico",r.historico).put("providencias",r.providencias)
            .put("responsavel",r.responsavel).put("responsavel_documento",r.responsavelDocumento)
            .put("agente_matricula",r.agenteMatricula).put("agente_nome",r.agenteNome)
            .put("outros_agentes",r.outrosAgentes).put("gps",r.gps)
            .put("atualizado_em",r.atualizadoEm)
        return JSONObject().put("numero_sigope",i.number).put("data",i.date)
            .put("categoria",i.category).put("solicitante",i.solicitante)
            .put("telefone",i.telefone).put("endereco",i.rua).put("bairro",i.bairro)
            .put("tipo",i.tipo).put("descricao",i.descricao).put("equipe",i.equipe)
            .put("prioridade",i.prioridade).put("status",i.status)
            .put("relatorio",report).put("total_fotos",store.photos(i.id).size)
    }

    private fun importRecord(id: String, data: JSONObject, exists: Boolean) {
        fun get(key: String, fallback: String = "") = data.optString(key,fallback)
        val cv = ContentValues().apply {
            if (!exists) put("id",id)
            put("number",get("numero_sigope",id))
            put("date",get("data",java.time.LocalDate.now().toString()))
            put("category",get("categoria","OCORRENCIAS"))
            put("solicitante",get("solicitante"));put("telefone",get("telefone"))
            put("rua",get("endereco"));put("bairro",get("bairro"))
            put("tipo",get("tipo"));put("descricao",get("descricao"))
            put("equipe",get("equipe"));put("prioridade",get("prioridade","Normal"))
            put("status",get("status","Aguardando atendimento"))
            put("sync_state","SINCRONIZADO")
            put("updated_at", LocalStore.now())
            if (!exists) put("created_at",LocalStore.now())
        }
        if (exists) {
            if (db.update("incidents",cv,"id=?",arrayOf(id)) != 1) error("Erro ao atualizar incidencia")
        } else {
            db.insertOrThrow("incidents",null,cv)
        }
        val report = data.optJSONObject("relatorio")
        if (report != null) {
            val r = ContentValues().apply {
                put("incident_id",id)
                for (key in listOf("historico","providencias","responsavel","responsavel_documento",
                    "agente_matricula","agente_nome","outros_agentes","gps","atualizado_em")) {
                    put(key,report.optString(key,""))
                }
            }
            db.insertWithOnConflict("reports",null,r,SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    private fun request(method: String, path: String, data: JSONObject?, token: String?): JSONObject {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout=12_000;conn.readTimeout=25_000
            conn.requestMethod=method
            conn.setRequestProperty("Accept","application/json")
            if (token != null) conn.setRequestProperty("Authorization","Bearer " + token)
            if (data != null) {
                conn.doOutput=true
                conn.setRequestProperty("Content-Type","application/json; charset=utf-8")
                conn.outputStream.use { it.write(data.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw HttpError(code,text)
            return JSONObject(text)
        } finally { conn.disconnect() }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer,0,n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sendFiles(item: Incident, token: String): Int {
        val existing = request("GET", "/api/v1/files?occurrence_id=" + java.net.URLEncoder.encode(item.id,"UTF-8"), null, token)
            .getJSONArray("files")
        val hashes = mutableSetOf<String>()
        for (n in 0 until existing.length()) hashes.add(existing.getJSONObject(n).optString("sha256"))
        val sources = mutableListOf<File>()
        store.photos(item.id).forEach { sources.add(File(it.path)) }
        if (item.pdfPath.isNotBlank()) sources.add(File(item.pdfPath))
        var sent = 0
        for (f in sources) {
            if (!f.isFile) throw IllegalStateException("Anexo local nao encontrado: " + f.name)
            if (f.length() > 16L*1024*1024) throw IllegalStateException("Anexo superior a 16 MB: " + f.name)
            val hash = sha256(f)
            if (hash in hashes) continue
            upload(item, f, token)
            hashes.add(hash)
            sent++
        }
        return sent
    }

    private fun upload(item: Incident, file: File, token: String) {
        val boundary = "Sentinela" + UUID.randomUUID().toString().replace("-","")
        val conn = URL(base + "/api/v1/files").openConnection() as HttpURLConnection
        try {
            conn.requestMethod="POST";conn.doOutput=true
            conn.connectTimeout=12_000;conn.readTimeout=60_000
            conn.setChunkedStreamingMode(65536)
            conn.setRequestProperty("Authorization","Bearer " + token)
            conn.setRequestProperty("Content-Type","multipart/form-data; boundary=" + boundary)
            val sep = "--" + boundary + "\r\n"
            conn.outputStream.use { out ->
                fun part(name: String, value: String) {
                    out.write((sep + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").toByteArray())
                }
                part("occurrence_id",item.id)
                part("category",item.category)
                val filename = file.name.replace("\"","").replace("\r","").replace("\n","")
                out.write((sep + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename +
                    "\"\r\nContent-Type: application/octet-stream\r\n\r\n").toByteArray())
                file.inputStream().use { it.copyTo(out) }
                out.write(("\r\n--" + boundary + "--\r\n").toByteArray())
            }
            val status=conn.responseCode
            if(status !in 200..299) throw HttpError(status,conn.errorStream?.bufferedReader()?.use {it.readText()}.orEmpty())
        } finally { conn.disconnect() }
    }
}
