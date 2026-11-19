package br.gov.rs.sapucaiadosul.sentinela

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class User(
    val id: String, val name: String, val matricula: String,
    val role: String, val signaturePath: String,
    val active: Boolean = true, val mustChangePassword: Boolean = false
)

data class Incident(
    val id: String, val number: String, val date: String, val category: String,
    val solicitante: String, val telefone: String, val rua: String, val bairro: String,
    val tipo: String, val descricao: String, val equipe: String,
    val prioridade: String, val status: String, val pdfPath: String,
    val syncState: String
)

data class Report(
    val incidentId: String,
    val historico: String = "", val providencias: String = "",
    val responsavel: String = "", val responsavelDocumento: String = "",
    val agenteMatricula: String = "", val agenteNome: String = "",
    val agenteSignature: String = "", val responsavelSignature: String = "",
    val outrosAgentes: String = "", val gps: String = "", val atualizadoEm: String = ""
)

data class Photo(val id: String, val incidentId: String, val path: String, val originalPath: String, val createdAt: String)

/** Evento de auditoria da ocorrência: ordem por ID, mesmo se o horário do aparelho for corrigido. */
data class AuditEvent(
    val id: Long, val at: String, val action: String,
    val actorName: String, val actorMatricula: String
)



/** Autorizações individuais do SIGOPE. Todas as ocorrências locais são visíveis a contas autenticadas. */
object Rights {
    const val VIEW = "VIEW"
    const val CREATE = "CREATE"
    const val ACCEPT = "ACCEPT"
    const val STATUS = "STATUS"
    const val BA = "BA"
    const val PHOTOS = "PHOTOS"
    const val FINISH = "FINISH"
    const val EXPORT = "EXPORT"
    const val REPORTS = "REPORTS"
    const val MAP = "MAP"
    const val USERS = "USERS"
    const val SETTINGS = "SETTINGS"
    val custom = listOf(CREATE,ACCEPT,STATUS,BA,PHOTOS,FINISH,EXPORT,REPORTS,MAP)
    val labels = mapOf(
        CREATE to "Registrar ocorrências", ACCEPT to "Aceitar atendimentos",
        STATUS to "Alterar status / deslocamento", BA to "Preencher boletim de atendimento",
        PHOTOS to "Anexar fotografias", FINISH to "Finalizar ocorrências",
        EXPORT to "Exportar documentos", REPORTS to "Consultar relatórios",
        MAP to "Abrir localização no mapa")
    fun defaults(role: String): Set<String> = when(role) {
        "ADMIN" -> custom.toSet() + setOf(VIEW,USERS,SETTINGS)
        "OPERADOR" -> custom.toSet() + VIEW
        "AGENTE" -> setOf(VIEW,ACCEPT,STATUS,BA,PHOTOS,FINISH,MAP)
        else -> setOf(VIEW,MAP)
    }
}

/** Dados operacionais do BA recebidos pelo operador e editaveis pelo agente. */
data class BAExtra(
    val solicitanteCpf: String = "",
    val tipoSolicitante: String = "",
    val tipoAtendimento: String = "",
    val responsavelTelefone: String = "",
    val responsavelVinculo: String = "",
    val tiposOcorrencia: String = "[]",
    val servidores: String = "[]",
    val enderecoComplemento: String = ""
)

class LocalStore(ctx: Context) : SQLiteOpenHelper(ctx, "sentinela_offline.db", null, 4) {
    private val prefs = ctx.getSharedPreferences("sentinela_prefs", Context.MODE_PRIVATE)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE users(
            id TEXT PRIMARY KEY, name TEXT NOT NULL, matricula TEXT UNIQUE NOT NULL,
            role TEXT NOT NULL, salt TEXT NOT NULL, pw_hash TEXT NOT NULL,
            signature_path TEXT NOT NULL DEFAULT '', active INTEGER NOT NULL DEFAULT 1,
            recovery_salt TEXT NOT NULL DEFAULT '', recovery_hash TEXT NOT NULL DEFAULT '',
            force_change_password INTEGER NOT NULL DEFAULT 0
        )""")
        db.execSQL("""CREATE TABLE incidents(
            id TEXT PRIMARY KEY, number TEXT UNIQUE NOT NULL, date TEXT NOT NULL,
            category TEXT NOT NULL, solicitante TEXT NOT NULL, telefone TEXT NOT NULL,
            rua TEXT NOT NULL, bairro TEXT NOT NULL, tipo TEXT NOT NULL,
            descricao TEXT NOT NULL, equipe TEXT NOT NULL, prioridade TEXT NOT NULL,
            status TEXT NOT NULL, pdf_path TEXT NOT NULL DEFAULT '',
            sync_state TEXT NOT NULL DEFAULT 'PENDENTE', created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )""")
        db.execSQL("""CREATE TABLE reports(
            incident_id TEXT PRIMARY KEY REFERENCES incidents(id),
            historico TEXT NOT NULL DEFAULT '', providencias TEXT NOT NULL DEFAULT '',
            responsavel TEXT NOT NULL DEFAULT '', responsavel_documento TEXT NOT NULL DEFAULT '',
            agente_matricula TEXT NOT NULL DEFAULT '', agente_nome TEXT NOT NULL DEFAULT '',
            agente_signature TEXT NOT NULL DEFAULT '', responsavel_signature TEXT NOT NULL DEFAULT '',
            outros_agentes TEXT NOT NULL DEFAULT '', gps TEXT NOT NULL DEFAULT '',
            atualizado_em TEXT NOT NULL DEFAULT ''
        )""")
        db.execSQL("""CREATE TABLE photos(
            id TEXT PRIMARY KEY, incident_id TEXT NOT NULL REFERENCES incidents(id),
            path TEXT NOT NULL, original_path TEXT NOT NULL, created_at TEXT NOT NULL
        )""")
        db.execSQL("""CREATE TABLE audit(
            id INTEGER PRIMARY KEY AUTOINCREMENT, incident_id TEXT,
            action TEXT NOT NULL, user_id TEXT NOT NULL, created_at TEXT NOT NULL
        )""")
        db.execSQL("""CREATE TABLE user_permissions(user_id TEXT NOT NULL, permission TEXT NOT NULL, allowed INTEGER NOT NULL, PRIMARY KEY(user_id,permission))""")
        db.execSQL("""CREATE TABLE incident_acceptances(incident_id TEXT NOT NULL, user_id TEXT NOT NULL, accepted_at TEXT NOT NULL, PRIMARY KEY(incident_id,user_id))""")
        db.execSQL("ALTER TABLE users ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX idx_incidents_date ON incidents(date)")
        db.execSQL("CREATE INDEX idx_photos_incident ON photos(incident_id)")
        createBaMeta(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migração não destrutiva: usuários, assinaturas e atendimentos antigos permanecem.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE users ADD COLUMN recovery_salt TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE users ADD COLUMN recovery_hash TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE users ADD COLUMN force_change_password INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE IF NOT EXISTS user_permissions(user_id TEXT NOT NULL, permission TEXT NOT NULL, allowed INTEGER NOT NULL, PRIMARY KEY(user_id,permission))")
            db.execSQL("CREATE TABLE IF NOT EXISTS incident_acceptances(incident_id TEXT NOT NULL, user_id TEXT NOT NULL, accepted_at TEXT NOT NULL, PRIMARY KEY(incident_id,user_id))")
            db.execSQL("ALTER TABLE users ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) createBaMeta(db)
    }

    private fun createBaMeta(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS ba_meta(
            incident_id TEXT PRIMARY KEY,
            solicitante_cpf TEXT NOT NULL DEFAULT '',
            tipo_solicitante TEXT NOT NULL DEFAULT '',
            tipo_atendimento TEXT NOT NULL DEFAULT '',
            responsavel_telefone TEXT NOT NULL DEFAULT '',
            responsavel_vinculo TEXT NOT NULL DEFAULT '',
            tipos_ocorrencia TEXT NOT NULL DEFAULT '[]',
            servidores TEXT NOT NULL DEFAULT '[]',
            endereco_complemento TEXT NOT NULL DEFAULT ''
        )""")
    }

    fun baMeta(id: String): BAExtra = readableDatabase.rawQuery(
        "SELECT solicitante_cpf,tipo_solicitante,tipo_atendimento,responsavel_telefone," +
            "responsavel_vinculo,tipos_ocorrencia,servidores,endereco_complemento FROM ba_meta WHERE incident_id=?",
        arrayOf(id)
    ).use { c ->
        if (!c.moveToFirst()) BAExtra()
        else BAExtra(c.getString(0),c.getString(1),c.getString(2),c.getString(3),
            c.getString(4),c.getString(5),c.getString(6),c.getString(7))
    }

    fun saveBaMeta(id: String, extra: BAExtra, author: String, syncedImport: Boolean = false) {
        if (!syncedImport) require(hasRight(author,Rights.CREATE) ||
            canOperate(id,author,Rights.BA)) { "Sem autorização para editar os dados do atendimento" }
        val cv=ContentValues().apply {
            put("incident_id", id);put("solicitante_cpf",extra.solicitanteCpf)
            put("tipo_solicitante",extra.tipoSolicitante);put("tipo_atendimento",extra.tipoAtendimento)
            put("responsavel_telefone",extra.responsavelTelefone)
            put("responsavel_vinculo",extra.responsavelVinculo)
            put("tipos_ocorrencia",extra.tiposOcorrencia)
            put("servidores",extra.servidores)
            put("endereco_complemento",extra.enderecoComplemento)
        }
        writableDatabase.insertWithOnConflict("ba_meta",null,cv,SQLiteDatabase.CONFLICT_REPLACE)
        if (!syncedImport) {
            writableDatabase.update("incidents",ContentValues().apply {
                put("sync_state","PENDENTE");put("updated_at",now())
            },"id=?",arrayOf(id))
        }
    }

    fun hasRight(userId: String, right: String): Boolean {
        val user = userById(userId) ?: return false
        if (!user.active) return false
        if (right == Rights.VIEW) return true // lista comum a todos os usuários autenticados
        if (right in setOf(Rights.USERS, Rights.SETTINGS)) return user.role == "ADMIN"
        if (user.role == "ADMIN") return true
        return readableDatabase.rawQuery(
            "SELECT allowed FROM user_permissions WHERE user_id=? AND permission=?",
            arrayOf(userId,right)).use { c ->
                if (c.moveToFirst()) c.getInt(0) == 1 else right in Rights.defaults(user.role)
            }
    }

    fun rightsForUser(userId: String): Set<String> = Rights.custom.filterTo(mutableSetOf()) { hasRight(userId,it) }

    /** Atualização de permissões feita somente por administrador ativo. */
    fun setUserRights(userId: String, rights: Set<String>, adminId: String) {
        require(hasRight(adminId,Rights.USERS)) { "Somente administrador pode atribuir permissões" }
        require(userById(userId) != null) { "Usuário não encontrado" }
        require(rights.all { it in Rights.custom }) { "Permissão inválida" }
        val db=writableDatabase
        db.beginTransaction()
        try {
            db.delete("user_permissions", "user_id=?", arrayOf(userId))
            Rights.custom.forEach { key ->
                db.insertOrThrow("user_permissions", null, ContentValues().apply {
                    put("user_id",userId);put("permission",key);put("allowed",if(key in rights) 1 else 0)
                })
            }
            logAction(db,null,"Permissões individuais de usuário alteradas",adminId)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Exclusão administrativa lógica: revoga login, preserva a autoria dos boletins/auditoria. */
    fun deleteUser(targetId: String, adminId: String) {
        require(hasRight(adminId,Rights.USERS)) { "Somente administrador pode excluir usuário" }
        require(targetId != adminId) { "Não é possível excluir sua própria conta" }
        val target=userById(targetId) ?: error("Usuário não encontrado")
        require(!(target.role == "ADMIN" && target.active && activeAdmins()<=1)) { "É necessário manter outro administrador ativo" }
        val db=writableDatabase
        db.beginTransaction()
        try {
            val updated=db.update("users", ContentValues().apply {
                put("active",0);put("deleted",1)
                put("recovery_salt","");put("recovery_hash","")
            }, "id=?", arrayOf(targetId))
            require(updated==1) { "Usuário não encontrado" }
            db.delete("user_permissions","user_id=?",arrayOf(targetId))
            logAction(db,null,"Usuário excluído (cadastro arquivado): ${target.matricula}",adminId)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun hasAccepted(incidentId: String,userId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM incident_acceptances WHERE incident_id=? AND user_id=? LIMIT 1",arrayOf(incidentId,userId)
    ).use { it.moveToFirst() }
    fun acceptanceNames(incidentId: String): List<String> = readableDatabase.rawQuery(
        "SELECT u.name || ' (' || u.matricula || ')' FROM incident_acceptances a JOIN users u ON u.id=a.user_id WHERE a.incident_id=? ORDER BY a.accepted_at",
        arrayOf(incidentId)
    ).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
    fun acceptIncident(incidentId: String,userId: String) {
        require(hasRight(userId,Rights.ACCEPT)) { "Você não tem permissão para aceitar atendimentos" }
        val incident=incident(incidentId) ?: error("Ocorrência não encontrada")
        require(incident.status!="Atendida") { "Ocorrência já concluída" }
        val db=writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("incident_acceptances",null,ContentValues().apply {
                put("incident_id",incidentId);put("user_id",userId);put("accepted_at",now())
            },SQLiteDatabase.CONFLICT_IGNORE)
            logAction(db,incidentId,"Atendimento aceito por ${userById(userId)?.matricula}",userId)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun canOperate(incidentId: String,userId: String,right: String): Boolean =
        hasRight(userId,right) && (hasAccepted(incidentId,userId) || userById(userId)?.role=="ADMIN")

    fun hasUsers() = readableDatabase.rawQuery("SELECT 1 FROM users LIMIT 1", null).use { it.moveToFirst() }

    private fun derivePassword(password: String, salt: ByteArray): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, 210_000, 256)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } finally { spec.clearPassword() }
    }

    fun createUser(name: String, matricula: String, role: String, password: String): User {
        require(name.isNotBlank() && matricula.isNotBlank()) { "Informe nome e matrícula" }
        validatePassword(password)
        require(role in setOf("ADMIN", "OPERADOR", "AGENTE", "CONSULTA")) { "Perfil inválido" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val salt64 = android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP)
        val user = User(UUID.randomUUID().toString(), name.trim(), matricula.trim(), role, "")
        val cv = ContentValues().apply {
            put("id", user.id); put("name", user.name); put("matricula", user.matricula)
            put("role", role); put("salt", salt64); put("pw_hash", derivePassword(password, salt))
        }
        writableDatabase.insertOrThrow("users", null, cv)
        return user
    }

    /** Provisiona o cache offline apenas apos autenticacao HTTPS valida no HUB. */
    fun provisionFromHub(name: String, matricula: String, role: String, password: String): User {
        require(name.isNotBlank() && matricula.isNotBlank())
        require(role in setOf("ADMIN","OPERADOR","AGENTE","CONSULTA"))
        validatePassword(password)
        val existing = userByMatricula(matricula)
        if (existing == null) return createUser(name,matricula,role,password)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val salt64 = android.util.Base64.encodeToString(salt,android.util.Base64.NO_WRAP)
        val cv = ContentValues().apply {
            put("name",name.trim());put("role",role);put("active",1);put("deleted",0)
            put("salt",salt64);put("pw_hash",derivePassword(password,salt))
        }
        writableDatabase.update("users",cv,"matricula=?", arrayOf(matricula.trim()))
        return login(matricula,password) ?: error("Falha ao provisionar credencial local")
    }

    fun login(matricula: String, password: String): User? {
        return readableDatabase.rawQuery(
            "SELECT id,name,matricula,role,signature_path,salt,pw_hash,force_change_password FROM users WHERE matricula=? AND active=1 AND deleted=0",
            arrayOf(matricula.trim())
        ).use { c ->
            if (!c.moveToFirst()) return@use null
            val salt = android.util.Base64.decode(c.getString(5), android.util.Base64.NO_WRAP)
            val proposed = derivePassword(password, salt).toByteArray(Charsets.UTF_8)
            val recorded = c.getString(6).toByteArray(Charsets.UTF_8)
            if (!MessageDigest.isEqual(proposed, recorded)) null
            else User(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), true, c.getInt(7) == 1)
        }
    }

    fun userByMatricula(mat: String): User? = readableDatabase.rawQuery(
        "SELECT id,name,matricula,role,signature_path,active,force_change_password FROM users WHERE matricula=? AND active=1 AND deleted=0", arrayOf(mat.trim())
    ).use { c -> if (c.moveToFirst()) c.user() else null }

    fun users(): List<User> = readableDatabase.rawQuery(
        "SELECT id,name,matricula,role,signature_path,active,force_change_password FROM users WHERE deleted=0 ORDER BY active DESC,name", null
    ).use { c -> buildList { while (c.moveToNext()) add(c.user()) } }

    private fun Cursor.user() = User(getString(0), getString(1), getString(2), getString(3), getString(4), getInt(5) == 1, getInt(6) == 1)

    fun userById(id: String): User? = readableDatabase.rawQuery(
        "SELECT id,name,matricula,role,signature_path,active,force_change_password FROM users WHERE id=? AND deleted=0", arrayOf(id)
    ).use { c -> if (c.moveToFirst()) c.user() else null }

    fun activeAdmins() = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM users WHERE role='ADMIN' AND active=1 AND deleted=0", null
    ).use { c -> c.moveToFirst(); c.getInt(0) }

    private fun validatePassword(password: String) {
        require(password.length >= 8 && password.any { it.isUpperCase() } &&
            password.any { it.isLowerCase() } && password.any { it.isDigit() } &&
            password.any { !it.isLetterOrDigit() }) {
            "Use 8 ou mais caracteres com maiúscula, minúscula, número e símbolo"
        }
    }

    private fun passwordRecord(password: String): Pair<String, String> {
        validatePassword(password)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP) to derivePassword(password, salt)
    }

    /** A senha jamais é exibida ou registrada; o administrador só define uma nova. */
    fun resetPasswordByAdmin(targetId: String, newPassword: String, actorId: String) {
        val actor = userById(actorId) ?: throw IllegalArgumentException("Administrador não identificado")
        require(actor.active && actor.role == "ADMIN") { "Sem permissão para redefinir senha" }
        require(userById(targetId) != null) { "Usuário não encontrado" }
        val (salt, hash) = passwordRecord(newPassword)
        val cv = ContentValues().apply {
            put("salt", salt); put("pw_hash", hash)
            put("force_change_password", if (targetId == actorId) 0 else 1)
        }
        writableDatabase.update("users", cv, "id=?", arrayOf(targetId))
        logAction(writableDatabase, null, "Senha de usuário redefinida pelo administrador", actorId)
    }

    fun requirePasswordChange(userId: String, actorId: String) {
        val admin = userById(actorId) ?: error("Administrador não encontrado")
        require(admin.active && admin.role == "ADMIN") { "Somente administrador pode determinar troca de senha" }
        writableDatabase.update("users", ContentValues().apply {
            put("force_change_password", 1)
        }, "id=?", arrayOf(userId))
    }

    /** Troca de senha após login com credencial temporária. */
    fun changeOwnPassword(userId: String, newPassword: String) {
        require(userById(userId)?.active == true) { "Conta inativa" }
        val (salt, hash) = passwordRecord(newPassword)
        writableDatabase.update("users", ContentValues().apply {
            put("salt", salt); put("pw_hash", hash); put("force_change_password", 0)
        }, "id=?", arrayOf(userId))
        logAction(writableDatabase, null, "Usuário definiu nova senha", userId)
    }

    fun updateUser(userId: String, name: String, matricula: String, role: String, active: Boolean, actorId: String) {
        val actor = userById(actorId) ?: error("Administrador não identificado")
        require(hasRight(actorId,Rights.USERS)) { "Sem autorização" }
        val old = userById(userId) ?: error("Usuário não encontrado")
        require(name.trim().isNotEmpty() && matricula.trim().isNotEmpty()) { "Informe nome e matrícula" }
        require(role in setOf("ADMIN", "OPERADOR", "AGENTE", "CONSULTA")) { "Perfil inválido" }
        require(!(userId == actorId && (!active || role != "ADMIN"))) { "Não é permitido retirar seu próprio acesso de administrador" }
        require(!(old.active && old.role == "ADMIN" && (!active || role != "ADMIN") && activeAdmins() <= 1)) {
            "É obrigatório manter ao menos um administrador ativo"
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update("users", ContentValues().apply {
                put("name", name.trim()); put("matricula", matricula.trim()); put("role", role)
                put("active", if (active) 1 else 0)
            }, "id=?", arrayOf(userId))
            if (role != old.role) db.delete("user_permissions","user_id=?",arrayOf(userId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        logAction(writableDatabase, null, "Cadastro de usuário atualizado", actorId)
    }

    /** Código de recuperação de uso único. Guarde fora do aparelho; jamais enviamos por e-mail ou web. */
    fun issueRecoveryCode(userId: String, actorId: String): String {
        val actor = userById(actorId) ?: error("Administrador não encontrado")
        require(actor.active && actor.role == "ADMIN") { "Somente administrador pode emitir código de recuperação" }
        require(userById(userId) != null) { "Usuário não encontrado" }
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        val code = (1..12).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
            .chunked(4).joinToString("-")
        val (salt, hash) = passwordRecordRecovery(code)
        writableDatabase.update("users", ContentValues().apply {
            put("recovery_salt", salt); put("recovery_hash", hash)
        }, "id=?", arrayOf(userId))
        logAction(writableDatabase, null, "Código de recuperação renovado", actorId)
        return code
    }

    private fun passwordRecordRecovery(code: String): Pair<String, String> {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP) to derivePassword(code, salt)
    }

    fun recoverWithCode(matricula: String, code: String, newPassword: String): Boolean {
        validatePassword(newPassword)
        val db = writableDatabase
        val cursor = db.rawQuery(
            "SELECT id,recovery_salt,recovery_hash FROM users WHERE matricula=? AND active=1 AND deleted=0",
            arrayOf(matricula.trim())
        )
        val record = cursor.use { c ->
            if (!c.moveToFirst()) null else Triple(c.getString(0), c.getString(1), c.getString(2))
        } ?: return false
        if (record.second.isBlank() || record.third.isBlank()) return false
        val proposed = derivePassword(code.trim().uppercase(), android.util.Base64.decode(record.second, android.util.Base64.NO_WRAP))
        if (!MessageDigest.isEqual(proposed.toByteArray(Charsets.UTF_8), record.third.toByteArray(Charsets.UTF_8))) return false
        val (newSalt, newHash) = passwordRecord(newPassword)
        db.beginTransaction()
        try {
            val affected = db.update("users", ContentValues().apply {
                put("salt", newSalt); put("pw_hash", newHash)
                put("recovery_salt", ""); put("recovery_hash", "")
                put("force_change_password", 0)
            }, "id=? AND recovery_hash=?", arrayOf(record.first, record.third))
            require(affected == 1) { "Código de recuperação já utilizado" }
            logAction(db, null, "Senha recuperada por código de uso único", record.first)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return true
    }

    fun saveRegisteredSignature(userId: String, path: String) {
        val cv = ContentValues().apply { put("signature_path", path) }
        writableDatabase.update("users", cv, "id=?", arrayOf(userId))
    }

    @Synchronized fun createIncident(
        category: String, solicitante: String, telefone: String, rua: String,
        bairro: String, tipo: String, descricao: String, equipe: String, prioridade: String,
        author: String
    ): Incident {
        require(hasRight(author,Rights.CREATE)) { "Você não tem permissão para registrar ocorrências" }
        require(category in setOf("OCORRENCIAS", "VISTORIAS", "APOIOS OPERACIONAIS", "INTERDIÇÕES"))
        require(solicitante.isNotBlank() && rua.isNotBlank()) { "Informe solicitante e endereço" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val year = LocalDate.now().year
            val seq = db.rawQuery("SELECT COUNT(*) FROM incidents WHERE date LIKE ?", arrayOf("$year%"))
                .use { c -> c.moveToFirst(); c.getInt(0) + 1 }
            val tag = prefs.getString("device_tag", null) ?: UUID.randomUUID().toString().take(4).uppercase().also {
                prefs.edit().putString("device_tag", it).apply()
            }
            // Número local rastreável e único por instalação; central deverá atribuir protocolo oficial.
            val number = "$year-$tag-${seq.toString().padStart(4, '0')}"
            val item = Incident(UUID.randomUUID().toString(), number, LocalDate.now().toString(), category,
                solicitante.trim(), telefone.trim(), rua.trim(), bairro.trim(), tipo.trim(),
                descricao.trim(), equipe.trim(), prioridade, "Aguardando atendimento", "", "PENDENTE")
            val cv = ContentValues().apply {
                put("id", item.id); put("number", number); put("date", item.date)
                put("category", item.category); put("solicitante", item.solicitante)
                put("telefone", item.telefone); put("rua", item.rua); put("bairro", item.bairro)
                put("tipo", item.tipo); put("descricao", item.descricao); put("equipe", item.equipe)
                put("prioridade", item.prioridade); put("status", item.status)
                put("created_at", now()); put("updated_at", now())
            }
            db.insertOrThrow("incidents", null, cv)
            logAction(db, item.id, "Ocorrência aberta: $number", author)
            db.setTransactionSuccessful()
            return item
        } finally { db.endTransaction() }
    }

    fun incidents(): List<Incident> = readableDatabase.rawQuery(
        "SELECT id,number,date,category,solicitante,telefone,rua,bairro,tipo,descricao,equipe,prioridade,status,pdf_path,sync_state " +
            "FROM incidents ORDER BY created_at DESC", null
    ).use { c -> buildList { while (c.moveToNext()) add(c.incident()) } }

    fun incident(id: String): Incident? = readableDatabase.rawQuery(
        "SELECT id,number,date,category,solicitante,telefone,rua,bairro,tipo,descricao,equipe,prioridade,status,pdf_path,sync_state FROM incidents WHERE id=?",
        arrayOf(id)
    ).use { c -> if (c.moveToFirst()) c.incident() else null }

    private fun Cursor.incident() = Incident(
        getString(0), getString(1), getString(2), getString(3), getString(4), getString(5),
        getString(6), getString(7), getString(8), getString(9), getString(10), getString(11),
        getString(12), getString(13), getString(14)
    )

    /** Campos conferidos em campo; não altera status, protocolo ou autoria. */
    fun updateIncidentDetails(id: String, solicitante: String, telefone: String, rua: String,
        bairro: String, tipo: String, author: String) {
        require(canOperate(id, author, Rights.BA)) { "Sem autorização para editar este BA" }
        require(solicitante.isNotBlank() && rua.isNotBlank()) {
            "Solicitante e endereço são obrigatórios"
        }
        writableDatabase.update("incidents",ContentValues().apply {
            put("solicitante",solicitante.trim());put("telefone",telefone.trim())
            put("rua",rua.trim());put("bairro",bairro.trim());put("tipo",tipo.trim())
            put("sync_state","PENDENTE");put("updated_at",now())
        },"id=?",arrayOf(id))
    }

    fun updateStatus(id: String, status: String, author: String) {
        require(status in setOf("Aguardando atendimento", "Equipe deslocada", "Em atendimento", "Aguardando apoio", "Aguardando retorno", "Atendida"))
        require(canOperate(id,author,Rights.STATUS)) { "Aceite a ocorrência antes de alterar o andamento" }
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("status", status); put("updated_at", now()); put("sync_state", "PENDENTE")
        }
        db.beginTransaction()
        try {
            db.update("incidents", cv, "id=?", arrayOf(id))
            logAction(db, id, "Situação: $status", author)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun report(id: String): Report = readableDatabase.rawQuery(
        "SELECT incident_id,historico,providencias,responsavel,responsavel_documento,agente_matricula," +
            "agente_nome,agente_signature,responsavel_signature,outros_agentes,gps,atualizado_em FROM reports WHERE incident_id=?",
        arrayOf(id)
    ).use { c ->
        if (!c.moveToFirst()) Report(id)
        else Report(
            c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5),
            c.getString(6), c.getString(7), c.getString(8), c.getString(9), c.getString(10), c.getString(11)
        )
    }

    fun saveReport(report: Report, author: String) {
        require(canOperate(report.incidentId,author,Rights.BA)) { "Sem autorização para preencher o boletim" }
        val cv = ContentValues().apply {
            put("incident_id", report.incidentId); put("historico", report.historico)
            put("providencias", report.providencias); put("responsavel", report.responsavel)
            put("responsavel_documento", report.responsavelDocumento)
            put("agente_matricula", report.agenteMatricula); put("agente_nome", report.agenteNome)
            put("agente_signature", report.agenteSignature)
            put("responsavel_signature", report.responsavelSignature)
            put("outros_agentes", report.outrosAgentes); put("gps", report.gps)
            put("atualizado_em", now())
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("reports", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            db.update("incidents", ContentValues().apply {put("sync_state", "PENDENTE");put("updated_at", now())}, "id=?", arrayOf(report.incidentId))
            logAction(db, report.incidentId, "BA salvo", author)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun addPhoto(incidentId: String, path: String, originalPath: String, author: String): Photo {
        require(canOperate(incidentId,author,Rights.PHOTOS)) { "Sem autorização para anexar fotografias" }
        val photo = Photo(UUID.randomUUID().toString(), incidentId, path, originalPath, now())
        writableDatabase.insertOrThrow("photos", null, ContentValues().apply {
            put("id", photo.id); put("incident_id", incidentId); put("path", path)
            put("original_path", originalPath); put("created_at", photo.createdAt)
        })
        writableDatabase.update("incidents", ContentValues().apply { put("sync_state", "PENDENTE") }, "id=?", arrayOf(incidentId))
        logAction(writableDatabase, incidentId, "Fotografia anexada", author)
        return photo
    }

    fun photos(incidentId: String): List<Photo> = readableDatabase.rawQuery(
        "SELECT id,incident_id,path,original_path,created_at FROM photos WHERE incident_id=? ORDER BY created_at", arrayOf(incidentId)
    ).use { c -> buildList { while(c.moveToNext()) add(Photo(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4))) } }

    fun conclude(id: String, pdfPath: String, author: String) {
        require(canOperate(id,author,Rights.FINISH)) { "Sem autorização para finalizar atendimento" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update("incidents", ContentValues().apply {
                put("status", "Atendida");put("pdf_path", pdfPath)
                put("sync_state", "PENDENTE");put("updated_at", now())
            }, "id=?", arrayOf(id))
            logAction(db, id, "BA e ocorrência concluídos localmente", author)
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }

    fun markSynced(id: String, author: String) {
        writableDatabase.update("incidents", ContentValues().apply { put("sync_state", "SINCRONIZADO") }, "id=?", arrayOf(id))
        logAction(writableDatabase, id, "Arquivado e sincronizado (ACK do servidor)", author)
    }

    /** Cronologia completa, incluindo os autores, para o PDF e consulta administrativa. */
    fun auditEvents(incidentId: String): List<AuditEvent> = readableDatabase.rawQuery(
        """SELECT a.id,a.created_at,a.action,
                  COALESCE(u.name, CASE WHEN a.user_id='servico_sync' THEN 'Serviço de sincronização' ELSE 'Usuário indisponível' END),
                  COALESCE(u.matricula,'')
             FROM audit a LEFT JOIN users u ON a.user_id=u.id
             WHERE a.incident_id=? ORDER BY a.id ASC""",
        arrayOf(incidentId)
    ).use { c -> buildList {
        while (c.moveToNext()) add(AuditEvent(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4)))
    } }

    fun audit(incidentId: String): List<String> = readableDatabase.rawQuery(
        "SELECT created_at, action FROM audit WHERE incident_id=? ORDER BY id DESC", arrayOf(incidentId)
    ).use { c -> buildList { while(c.moveToNext()) add("${c.getString(0)} — ${c.getString(1)}") } }

    private fun logAction(db: SQLiteDatabase, id: String?, action: String, author: String) {
        db.insert("audit", null, ContentValues().apply {
            put("incident_id", id);put("action", action);put("user_id", author);put("created_at", now())
        })
    }

    companion object { fun now(): String = java.time.OffsetDateTime.now().toString() }
}
