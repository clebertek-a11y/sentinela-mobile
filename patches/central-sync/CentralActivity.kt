package br.gov.rs.sapucaiadosul.sentinela

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.UUID

/**
 * Módulo SIGADE Central para homologação. Login institucional separado do local.
 * A lista mostra ocorrências oficiais ou pendentes do próprio modo Central;
 * nunca importa automaticamente registros do banco antigo deste aparelho.
 */
class CentralActivity : Activity() {
    private val background = Color.rgb(246, 248, 252)
    private val navy = Color.rgb(20, 40, 73)
    private val handler = Handler(Looper.getMainLooper())
    private var performing = false
    private var active = false
    private lateinit var content: LinearLayout
    private val poll = object : Runnable {
        override fun run() {
            if (active && CentralSync.signedIn(this@CentralActivity)) {
                refresh(true)
                handler.postDelayed(this, 20_000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = navy
        window.navigationBarColor = navy
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        showHome()
    }

    override fun onResume() {
        super.onResume()
        active = true
        if (CentralSync.signedIn(this)) {
            handler.removeCallbacks(poll)
            handler.postDelayed(poll, 3_000L)
        }
    }
    override fun onPause() {
        active = false
        handler.removeCallbacks(poll)
        super.onPause()
    }

    private fun begin(title: String): LinearLayout {
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26, 28, 26, 70)
            setBackgroundColor(this@CentralActivity.background)
        }
        scroll.addView(content)
        setContentView(scroll)
        label(title, 22f, true)
        return content
    }

    private fun label(text: String, size: Float = 15f, bold: Boolean = false): TextView {
        val view = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(navy)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 10, 0, 10)
        }
        content.addView(view)
        return view
    }

    private fun input(hint: String, password: Boolean = false, initial: String = ""): EditText {
        val field = EditText(this).apply {
            this.hint = hint
            setText(initial)
            setSingleLine(!hint.contains("Descrição"))
            setTextColor(Color.BLACK)
            textSize = 16f
            if (password) inputType = 129
            else inputType = 1
            setPadding(14, 12, 14, 14)
        }
        content.addView(field)
        return field
    }

    private fun button(title: String, action: () -> Unit): Button {
        val b = Button(this).apply { text = title; isAllCaps = false; minHeight = 52 }
        b.setOnClickListener { if (!performing) action() }
        content.addView(b)
        return b
    }

    private fun task(work: () -> Unit, after: () -> Unit = { showHome() }) {
        if (performing) return
        performing = true
        Thread {
            val result = runCatching(work)
            runOnUiThread {
                performing = false
                if (isDestroyed || isFinishing) return@runOnUiThread
                result.onSuccess { after() }.onFailure {
                    Toast.makeText(this, it.message ?: "Não foi possível sincronizar.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showHome() {
        begin("SIGADE CENTRAL")
        label("Homologação • dados compartilhados somente após sincronização confirmada.")
        label("Atendimentos do SENTINELA local antigo NÃO são enviados automaticamente. Não utilize registros reais antes da homologação.", 13f)
        if (!CentralSync.configured(this) || !CentralSync.signedIn(this)) {
            loginScreen()
            return
        }
        label("Conectado à fonte central SIGADE. O SERVIDOR02 é o arquivo institucional, não um segundo banco de ocorrências.", 13f)
        label("Pendências: " + CentralSync.pendingCount(this) +
            " • Conflitos: " + CentralSync.conflicts(this), 13f, true)
        button("Atualizar e sincronizar agora") { refresh(false) }
        button("+ Registrar ocorrência no SIGADE") { createScreen() }
        button("Sair da conta central") {
            AlertDialog.Builder(this)
                .setTitle("Sair do SIGADE Central?")
                .setMessage("As pendências locais serão preservadas, mas não poderão ser enviadas sem novo login.")
                .setPositiveButton("Sair") { _, _ -> CentralSync.signOut(this); showHome() }
                .setNegativeButton("Cancelar", null).show()
        }
        val cases = CentralSync.cached(this)
        if (cases.isEmpty()) label("Nenhuma ocorrência sincronizada. Verifique a conexão e a ativação do banco central.")
        for (obj in cases.take(150)) {
            val id = obj.optString("id")
            val version = obj.optInt("mobile_versao", 0)
            val number = obj.optString("numero_ocorrencia")
            val status = obj.optString("status")
            label(number + " • " + obj.optString("tipo_ocorrencia") +
                "\n" + obj.optString("rua") + " • " + status +
                "\nVersão " + version, 14f, true)
            if (status in setOf("Aguardando atendimento", "Agendada") && version > 0) {
                button("Registrar saída — A caminho") {
                    task({
                        CentralSync.enqueueDeparture(this, id)
                        CentralSync.synchronize(this)
                    })
                }
            }
            button("Abrir BA no SIGADE Web") {
                // O navegador requer login próprio. NUNCA enviar JWT como parâmetro da URL.
                AlertDialog.Builder(this)
                    .setTitle("Abrir Boletim de Atendimento")
                    .setMessage("O BA utiliza o login institucional no navegador. Abra somente após a publicação homologada do SIGADE; os registros locais antigos continuam separados.")
                    .setPositiveButton("Abrir") { _, _ ->
                        val url = "https://sigade.lovable.app/ba/" + Uri.encode(id)
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                    .setNegativeButton("Cancelar", null).show()
            }
        }
    }

    private fun loginScreen() {
        label("Conexão com o SIGADE")
        label("Use a URL pública do projeto Supabase e a chave publishable/anon, nunca service_role. O login central é separado da senha local do SENTINELA.", 13f)
        val url = input("URL HTTPS do Supabase", initial = CentralSync.base(this))
        val key = input("Chave pública / publishable", initial = CentralSync.key(this))
        val email = input("E-mail da conta central")
        val password = input("Senha da conta central", password = true)
        button("Conectar ao SIGADE") {
            val u = url.text.toString().trim()
            val k = key.text.toString().trim()
            val mail = email.text.toString().trim()
            val pwd = password.text.toString()
            password.setText("")
            task({
                CentralSync.configure(this, u, k)
                CentralSync.signIn(this, mail, pwd)
                CentralSync.synchronize(this)
            })
        }
    }

    private fun createScreen() {
        begin("Nova ocorrência — SIGADE")
        label("Você pode registrar sem conexão. O número oficial será atribuído pelo SIGADE ao sincronizar; até lá, o registro é provisório.", 13f)
        val solicitante = input("Solicitante")
        val telefone = input("Telefone")
        val rua = input("Rua")
        val bairro = input("Bairro")
        val tipo = input("Tipo da ocorrência")
        val descricao = input("Descrição do chamado")
        button("Registrar e sincronizar") {
            val payload = JSONObject()
                .put("solicitante", solicitante.text.toString().trim())
                .put("telefone", telefone.text.toString().trim())
                .put("rua", rua.text.toString().trim())
                .put("bairro", bairro.text.toString().trim())
                .put("tipo_ocorrencia", tipo.text.toString().trim())
                .put("descricao", descricao.text.toString().trim())
                .put("prioridade", "Normal")
            try {
                CentralSync.enqueueCreate(this, payload)
                // A fila persistiu: volte à lista antes da rede, para não duplicar no retry.
                showHome()
                refresh(true)
            } catch (e: Exception) {
                Toast.makeText(this, e.message ?: "Verifique os campos.", Toast.LENGTH_LONG).show()
            }
        }
        button("Voltar sem registrar") { showHome() }
    }

    private fun refresh(silent: Boolean) {
        if (performing) return
        task({ CentralSync.synchronize(this) }) {
            if (!silent) showHome()
            // Não reconstruir a tela durante digitação.
        }
    }
}
