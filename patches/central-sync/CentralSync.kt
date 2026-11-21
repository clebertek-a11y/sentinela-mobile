package br.gov.rs.sapucaiadosul.sentinela

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.security.KeyStore
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Integração de homologação. Banco oficial SIGADE = única fonte de verdade.
 * A fila local NUNCA é tratada como arquivamento no SERVIDOR02.
 * Chamar operações de rede somente fora da thread principal.
 */
internal object CentralSync {
    private const val PREFS = "sigade_central_v1"
    private const val KEY_ALIAS = "sigade_mobile_central_session"
    private const val RECONCILIAR_MS = 10 * 60 * 1000L
    data class Summary(val total: Int, val pendentes: Int, val conflitos: Int, val atualizadoEm: String)
    class HttpError(val code: Int, message: String) : Exception(message)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun configured(ctx: Context): Boolean {
        val p = prefs(ctx)
        return p.getString("base", "").orEmpty().startsWith("https://") &&
            p.getString("key", "").orEmpty().isNotBlank()
    }

    fun configure(ctx: Context, base: String, publishableKey: String) {
        val url = URL(base.trim().trimEnd('/'))
        require(url.protocol == "https" && url.host.isNotBlank() &&
            url.userInfo == null && url.query == null && url.ref == null &&
            url.port == -1) { "Informe HTTPS válido, sem parâmetros ou senha." }
        require(publishableKey.length in 12..512) { "Chave pública inválida." }
        val old = prefs(ctx)
        if (old.getString("base", "") != url.toString() ||
            old.getString("key", "") != publishableKey) {
            signOut(ctx)
            // Não reutilizar fila/cache de outro projeto Supabase.
            ctx.deleteDatabase("sigade_central_cache.db")
        }
        old.edit().putString("base", url.toString())
            .putString("key", publishableKey.trim()).apply()
    }

    fun base(ctx: Context) = prefs(ctx).getString("base", "").orEmpty()
    fun key(ctx: Context) = prefs(ctx).getString("key", "").orEmpty()

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 12) { "Sessão inválida." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    private fun session(ctx: Context): JSONObject? {
        val encrypted = prefs(ctx).getString("session", "") ?: ""
        if (encrypted.isBlank()) return null
        return runCatching { JSONObject(decrypt(encrypted)) }.getOrNull()
    }

    fun signedIn(ctx: Context): Boolean = session(ctx)?.optString("access").orEmpty().isNotEmpty()

    fun signOut(ctx: Context) {
        prefs(ctx).edit().remove("session").apply()
        // Não apagar fila/casos: registros offline podem precisar de revisão pelo administrador.
    }

    private fun saveSession(ctx: Context, response: JSONObject) {
        val access = response.getString("access_token")
        val refresh = response.getString("refresh_token")
        val uid = response.getJSONObject("user").getString("id")
        val expires = System.currentTimeMillis() + response.optLong("expires_in", 3600) * 1000L
        prefs(ctx).edit().putString("session", encrypt(JSONObject()
            .put("access", access).put("refresh", refresh)
            .put("user_id", uid).put("expires", expires).toString())).apply()
    }

    private fun post(ctx: Context, path: String, body: JSONObject, token: String? = null): JSONObject {
        require(configured(ctx)) { "Configure o acesso institucional ao SIGADE Central." }
        val url = URL(base(ctx) + path)
        val conn = url.openConnection() as HttpsURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 25_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("apikey", key(ctx))
            conn.setRequestProperty("Cache-Control", "no-store")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer " + token)
            val data = body.toString().toByteArray(Charsets.UTF_8)
            require(data.size <= 64 * 1024) { "Operação acima do tamanho permitido." }
            conn.setFixedLengthStreamingMode(data.size)
            conn.outputStream.use { it.write(data) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(8000) } ?: ""
            if (status !in 200..299) {
                // Não incluir o corpo recebido em logs nem expor credenciais.
                val msg = if (text.contains("MOBILE_CONFLITO")) "MOBILE_CONFLITO" else
                    if (status == 401 || status == 403) "Acesso institucional recusado." else
                        "Falha HTTP " + status + " no serviço central."
                throw HttpError(status, msg)
            }
            return JSONObject(text)
        } finally { conn.disconnect() }
    }

    fun signIn(ctx: Context, email: String, password: String) {
        require(email.contains('@') && password.isNotEmpty()) { "Informe e-mail e senha institucionais." }
        val payload = JSONObject().put("email", email.trim()).put("password", password)
        val data = post(ctx, "/auth/v1/token?grant_type=password", payload)
        saveSession(ctx, data)
    }

    private fun access(ctx: Context): String {
        var s = session(ctx) ?: error("Entre com uma conta SIGADE autorizada.")
        if (s.optLong("expires", 0) < System.currentTimeMillis() + 90_000) {
            val result = post(ctx, "/auth/v1/token?grant_type=refresh_token",
                JSONObject().put("refresh_token", s.getString("refresh")))
            saveSession(ctx, result)
            s = session(ctx) ?: error("Sessão indisponível.")
        }
        return s.getString("access")
    }

    private fun rpc(ctx: Context, name: String, body: JSONObject): JSONObject {
        return post(ctx, "/rest/v1/rpc/" + name, body, access(ctx))
    }

    fun enqueueCreate(ctx: Context, dados: JSONObject): String {
        require(signedIn(ctx)) { "Entre no SIGADE Central antes de registrar." }
        val required = listOf("solicitante", "rua", "bairro", "tipo_ocorrencia", "descricao")
        for (field in required) require(dados.optString(field).isNotBlank()) { "Preencha " + field }
        val id = UUID.randomUUID().toString()
        val provisional = JSONObject(dados.toString())
            .put("id", id).put("numero_ocorrencia", "LOCAL-" + id.take(8))
            .put("status", "Aguardando atendimento").put("mobile_versao", 0)
            .put("data_hora_abertura", Instant.now().toString())
        CentralCache(ctx).use { db ->
            db.queue(UUID.randomUUID().toString(), "criar", id, 0, dados)
            db.upsert(id, 0, provisional)
        }
        return id
    }

    fun enqueueDeparture(ctx: Context, id: String) {
        require(signedIn(ctx)) { "Entre no SIGADE Central." }
        CentralCache(ctx).use { db ->
            require(!db.hasPending(id)) { "Aguarde sincronizar alterações anteriores desta ocorrência." }
            val obj = db.case(id) ?: error("Ocorrência ainda não carregada.")
            val version = obj.optInt("mobile_versao", 0)
            require(version > 0) { "Aguardando número oficial do SIGADE." }
            require(obj.optString("status") in setOf("Aguardando atendimento", "Agendada")) {
                "Esta ocorrência já está em outra etapa."
            }
            db.queue(UUID.randomUUID().toString(), "status", id, version,
                JSONObject().put("status", "Equipe deslocada"))
        }
    }

    // Operações locais têm IDs imutáveis. Retry nunca cria outra ocorrência.
    fun synchronize(ctx: Context): Summary {
        require(signedIn(ctx)) { "Faça login no SIGADE Central." }
        CentralCache(ctx).use { db ->
            for (op in db.pending()) {
                try {
                    val response = rpc(ctx, "mobile_comando", JSONObject()
                        .put("p_operacao_id", op.id).put("p_tipo", op.type)
                        .put("p_ocorrencia_id", op.caseId).put("p_base_versao", op.version)
                        .put("p_dados", JSONObject(op.body)))
                    val updated = response.optJSONObject("registro")
                        ?: error("O servidor não confirmou o registro. A operação seguirá pendente.")
                    db.upsert(op.caseId, updated.optInt("mobile_versao"), updated)
                    db.done(op.id)
                } catch (e: HttpError) {
                    if (e.message == "MOBILE_CONFLITO") db.conflict(op.id)
                    else throw e
                }
            }
            val oldCursor = db.value("cursor").toLongOrNull() ?: 0L
            var after = (oldCursor - 100L).coerceAtLeast(0L)
            var hasMoreDelta = true
            for (attempt in 0 until 100) {
                if (!hasMoreDelta) break
                val page = rpc(ctx, "mobile_mudancas", JSONObject()
                    .put("p_apos", after).put("p_limite", 100))
                val arr = page.optJSONArray("itens") ?: JSONArray()
                for (n in 0 until arr.length()) {
                    val item = arr.getJSONObject(n)
                    val caseId = item.optJSONObject("ocorrencia")?.optString("id").orEmpty()
                        .ifBlank { item.optString("ocorrencia_id") }
                    if (caseId.isBlank() || db.hasPending(caseId)) continue
                    if (item.optBoolean("removida")) db.remove(caseId)
                    else item.optJSONObject("ocorrencia")?.let {
                        db.upsert(caseId, it.optInt("mobile_versao"), it)
                    }
                }
                val next = page.optLong("proximo", after)
                require(next >= after) { "Cursor de sincronização inválido." }
                after = next
                db.putValue("cursor", maxOf(oldCursor, after).toString())
                hasMoreDelta = page.optBoolean("has_more", false)
            }
            val lastFull = db.value("snapshot_at").toLongOrNull() ?: 0
            if (System.currentTimeMillis() - lastFull > RECONCILIAR_MS) {
                var key: String? = null
                var completed = false
                repeat(100) {
                    if (completed) return@repeat
                    val page = rpc(ctx, "mobile_snapshot", JSONObject()
                        .put("p_depois", key ?: JSONObject.NULL).put("p_limite", 100))
                    val records = page.optJSONArray("itens") ?: JSONArray()
                    for (n in 0 until records.length()) {
                        val item = records.getJSONObject(n)
                        val id = item.getString("id")
                        if (!db.hasPending(id)) db.upsert(id, item.optInt("mobile_versao"), item)
                    }
                    val next = page.optString("proximo", "")
                    completed = !page.optBoolean("has_more", false)
                    if (!completed && (next.isBlank() || next == key)) error("Cursor do snapshot não avançou.")
                    key = next
                }
                if (completed) db.putValue("snapshot_at", System.currentTimeMillis().toString())
            }
            return Summary(db.cases().size, db.pending().size, db.conflictCount(),
                Instant.now().toString())
        }
    }

    fun cached(ctx: Context): List<JSONObject> =
        CentralCache(ctx).use { it.cases() }

    fun conflicts(ctx: Context): Int = CentralCache(ctx).use { it.conflictCount() }
    fun pendingCount(ctx: Context): Int = CentralCache(ctx).use { it.pending().size }
}

/** Banco ISOLADO do modo central. Não altera os cadastros legados do SENTINELA. */
internal class CentralCache(ctx: Context) : SQLiteOpenHelper(ctx,
    "sigade_central_cache.db", null, 1) {
    data class Op(val id: String, val type: String, val caseId: String,
                  val version: Int, val body: String)
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE casos(id TEXT PRIMARY KEY,versao INTEGER NOT NULL,dados TEXT NOT NULL)")
        db.execSQL("CREATE TABLE fila(id TEXT PRIMARY KEY,tipo TEXT NOT NULL,ocorrencia_id TEXT NOT NULL,base_versao INTEGER NOT NULL,dados TEXT NOT NULL,estado TEXT NOT NULL DEFAULT 'PENDENTE')")
        db.execSQL("CREATE TABLE estado(chave TEXT PRIMARY KEY,valor TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun upsert(id: String, version: Int, value: JSONObject) {
        writableDatabase.execSQL("INSERT INTO casos(id,versao,dados) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET versao=excluded.versao,dados=excluded.dados WHERE excluded.versao>=casos.versao",
            arrayOf(id, version, value.toString()))
    }
    fun case(id: String): JSONObject? = readableDatabase.rawQuery("SELECT dados FROM casos WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst()) JSONObject(it.getString(0)) else null
    }
    fun cases(): List<JSONObject> = readableDatabase.rawQuery(
        "SELECT dados FROM casos ORDER BY id DESC", null).use { c ->
        buildList { while(c.moveToNext()) add(JSONObject(c.getString(0))) }
    }
    fun remove(id: String) { writableDatabase.delete("casos", "id=?", arrayOf(id)) }
    fun queue(id: String, type: String, caseId: String, version: Int, data: JSONObject) {
        writableDatabase.execSQL("INSERT INTO fila(id,tipo,ocorrencia_id,base_versao,dados) VALUES(?,?,?,?,?)",
            arrayOf(id, type, caseId, version, data.toString()))
    }
    fun pending(): List<Op> = readableDatabase.rawQuery(
        "SELECT id,tipo,ocorrencia_id,base_versao,dados FROM fila WHERE estado='PENDENTE' ORDER BY rowid",
        null).use { c -> buildList {
        while(c.moveToNext()) add(Op(c.getString(0),c.getString(1),c.getString(2),c.getInt(3),c.getString(4)))
    } }
    fun hasPending(id: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM fila WHERE ocorrencia_id=? AND estado IN ('PENDENTE','CONFLITO') LIMIT 1",
        arrayOf(id)).use { it.moveToFirst() }
    fun done(id: String) { writableDatabase.delete("fila","id=?",arrayOf(id)) }
    fun conflict(id: String) { writableDatabase.execSQL("UPDATE fila SET estado='CONFLITO' WHERE id=?", arrayOf(id)) }
    fun conflictCount(): Int = readableDatabase.rawQuery(
        "SELECT count(*) FROM fila WHERE estado='CONFLITO'",null).use { it.moveToFirst();it.getInt(0) }
    fun putValue(k: String,v: String) { writableDatabase.execSQL(
        "INSERT OR REPLACE INTO estado(chave,valor) VALUES(?,?)",arrayOf(k,v)) }
    fun value(k: String): String = readableDatabase.rawQuery(
        "SELECT valor FROM estado WHERE chave=?", arrayOf(k)).use { if(it.moveToFirst()) it.getString(0) else "" }
}
