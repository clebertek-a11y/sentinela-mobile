package br.gov.rs.sapucaiadosul.sentinela

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.app.job.JobScheduler
import android.content.ContentValues
import android.content.Intent
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.text.method.HideReturnsTransformationMethod
import android.view.inputmethod.EditorInfo
import android.view.WindowManager
import android.content.pm.ApplicationInfo
import android.text.Editable
import android.text.TextWatcher
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import android.net.ConnectivityManager
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate

class MainActivity : Activity() {
    private lateinit var store: LocalStore
    private var currentUser: User? = null
    private var attachmentIncidentId: String? = null
    private var cameraUri: Uri? = null
    private var selectedIncidentId: String? = null
    private var availableUpdate: UpdateChecker.Release? = null
    private var checkingUpdate = false
    private var pilotUnlockVerified = false
    private val prefs by lazy { getSharedPreferences("sync_settings", Context.MODE_PRIVATE) }

    private val navy = Color.rgb(20, 40, 73)
    private val orange = Color.rgb(242, 130, 37)
    private val bg = Color.rgb(246, 248, 251)
    private val ink = Color.rgb(28, 39, 58)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = navy
        window.navigationBarColor = navy
        store = LocalStore(this)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (store.hasUsers()) showLogin() else showBootstrap()
        if (!prefs.getString("url","").isNullOrBlank()) { SyncJobService.periodic(this); SyncJobService.schedule(this) }
        checkForUpdates(false)
    }

    private fun isTestBuild() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private fun sp(dp: Int) = (dp * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun shape(color: Int, radius: Int = 14) = GradientDrawable().apply {
        setColor(color); cornerRadius = sp(radius).toFloat()
    }
    private fun vertical(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun label(text: String, size: Float = 15f, bold: Boolean = false, color: Int = ink): TextView =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            setPadding(0, sp(5), 0, sp(5))
            if (bold) setTypeface(null, Typeface.BOLD)
        }
    private fun button(text: String, primary: Boolean = true, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text; textSize = 14f; isAllCaps = false
        setTextColor(if (primary) Color.WHITE else navy)
        background = shape(if (primary) navy else Color.rgb(228, 234, 242), 12)
        setPadding(sp(12), sp(11), sp(12), sp(11))
        setOnClickListener { onClick() }
    }
    private fun field(parent: LinearLayout, title: String, hint: String = "", value: String = "", password: Boolean = false): EditText {
        parent.addView(label(title, 13f, true))
        val multiline = title.contains("histórico", true) || title.contains("providências", true) || title.contains("descrição", true)
        val input = EditText(this).apply {
            setText(value); this.hint = hint; textSize = 16f; setTextColor(ink)
            setHintTextColor(Color.rgb(105, 114, 130))
            setSingleLine(!multiline)
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = if (multiline) EditorInfo.IME_FLAG_NO_ENTER_ACTION else EditorInfo.IME_ACTION_NEXT
            setPadding(sp(12), sp(12), sp(12), sp(12))
            setBackgroundColor(Color.TRANSPARENT)
            minHeight = sp(48)
        }
        val wrapper = row().apply {
            val outline = GradientDrawable().apply {
                setColor(Color.rgb(249, 251, 254)); cornerRadius = sp(10).toFloat()
                setStroke(sp(1), Color.rgb(171, 183, 202))
            }
            background = outline
        }
        wrapper.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        if (password) {
            val toggle = label("Mostrar", 13f, true, navy).apply {
                contentDescription = "Mostrar ou ocultar senha"
                setPadding(sp(9), sp(13), sp(12), sp(13))
                setOnClickListener {
                    val showing = input.transformationMethod !is PasswordTransformationMethod
                    input.transformationMethod = if (showing) PasswordTransformationMethod.getInstance() else HideReturnsTransformationMethod.getInstance()
                    text = if (showing) "Mostrar" else "Ocultar"
                    input.setSelection(input.text.length)
                }
            }
            wrapper.addView(toggle)
        }
        parent.addView(wrapper, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = sp(14) })
        return input
    }
    private fun section(content: LinearLayout, title: String): LinearLayout {
        val card = vertical().apply { background = shape(Color.WHITE, 16); setPadding(sp(15), sp(13), sp(15), sp(15)) }
        card.addView(label(title, 17f, true, navy))
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = sp(12) })
        return card
    }
    private fun screen(title: String, subtitle: String = "", back: (() -> Unit)? = null): LinearLayout {
        val root = vertical().apply { setBackgroundColor(bg) }
        val header = vertical().apply {
            setPadding(sp(20), sp(24), sp(20), sp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(navy, Color.rgb(38, 65, 105)))
        }
        if (back != null) header.addView(button("‹ Voltar", false, back), LinearLayout.LayoutParams(-2, -2).apply {bottomMargin = sp(10)})
        header.addView(label("SENTINELA • DEFESA CIVIL • BETA", 12f, true, orange))
        header.addView(label(title, 25f, true, Color.WHITE))
        if (subtitle.isNotBlank()) header.addView(label(subtitle, 12f, false, Color.rgb(215, 225, 237)))
        root.addView(header)
        val stripe = View(this).apply {setBackgroundColor(orange)}
        root.addView(stripe, LinearLayout.LayoutParams(-1, sp(4)))
        val body = vertical().apply { setPadding(sp(17), sp(17), sp(17), sp(44)) }
        root.addView(body, LinearLayout.LayoutParams(-1, -2))
        root.addView(label("IAntzen Tecnologia — Soluções Inteligentes",11f,false,Color.rgb(103,118,139)).apply {gravity=Gravity.CENTER;setPadding(0,0,0,sp(15))})
        // O cabeçalho também rola, liberando espaço quando o teclado virtual está aberto.
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        scroll.addView(root)
        setContentView(scroll)
        return body
    }
    private fun has(right: String): Boolean = currentUser?.let { store.hasRight(it.id, right) } == true
    private fun canEdit() = has(Rights.BA)
    private fun canOpen() = has(Rights.CREATE)

    private val darkBg get() = Color.rgb(5, 26, 51)
    private val darkCard get() = Color.rgb(20, 48, 81)
    private val darkBorder get() = Color.rgb(59, 94, 128)
    private val coolWhite get() = Color.rgb(239, 244, 252)

    private fun darkShape(fill: Int = darkCard, radius: Int = 14, stroke: Int = darkBorder): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = sp(radius).toFloat()
            setStroke(sp(1), stroke)
        }

    private fun darkLabel(value: String, size: Float = 14f, bold: Boolean = false, color: Int = coolWhite): TextView =
        label(value, size, bold, color).apply { includeFontPadding = true }

    private fun darkButton(title: String, highlight: Boolean = false, click: () -> Unit): TextView =
        darkLabel(title, 13.5f, true, Color.WHITE).apply {
            background = darkShape(if (highlight) orange else Color.rgb(24, 62, 102), 12,
                if (highlight) Color.rgb(253, 166, 88) else darkBorder)
            gravity = Gravity.CENTER
            setPadding(sp(12), sp(10), sp(12), sp(10))
            minHeight = sp(46)
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
        }

    private fun darkPill(value: String, fill: Int): TextView =
        darkLabel(value, 11f, true).apply {
            background = darkShape(fill, 10, fill)
            gravity = Gravity.CENTER
            setPadding(sp(9), sp(4), sp(9), sp(4))
        }

    private fun dashboardStat(title: String, count: Int, accent: Int): LinearLayout = vertical().apply {
        background = darkShape(Color.rgb(15, 42, 73), 14, accent)
        setPadding(sp(12), sp(8), sp(12), sp(10))
        addView(darkLabel(count.toString(), 25f, true, accent))
        addView(darkLabel(title, 12f, true))
    }

    private fun openingScreen(title: String, sub: String): LinearLayout {
        val shell = vertical().apply { setBackgroundColor(darkBg) }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = vertical()
        scroll.addView(content)
        shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(shell)
        return content
    }

    private fun addBottomNav(root: LinearLayout, selected: String) {
        val bar = row().apply {
            background = darkShape(Color.rgb(14, 39, 72), 17, darkBorder)
            setPadding(sp(5), sp(5), sp(5), sp(5))
            gravity = Gravity.CENTER
        }
        val tabs = mutableListOf(Triple("⌂", "Início", { dashboard() }))
        tabs.add(Triple("☷", "Ocorrências", { caseListScreen() }))
        if (canOpen()) tabs.add(Triple("+", "Registrar", { newIncident() }))
        if (has(Rights.REPORTS)) tabs.add(Triple("▥", "Relatórios", { reportsScreen() }))
        if (has(Rights.USERS)) tabs.add(Triple("♙", "Usuários", { userManagement() }))
        if (!has(Rights.USERS) && !canOpen()) tabs.add(Triple("▤", "Meus BAs", { myReportsScreen() }))
        tabs.forEach { (icon, name, action) ->
            val cell=vertical().apply {
                gravity=Gravity.CENTER
                background=if (selected == name) darkShape(Color.rgb(32, 71, 109), 12, Color.rgb(32, 71, 109)) else null
                setPadding(sp(1), sp(5), sp(1), sp(5))
                isClickable=true; isFocusable=true
                setOnClickListener { action() }
            }
            cell.addView(darkLabel(icon, 22f, true, if (selected==name) orange else coolWhite).apply {gravity=Gravity.CENTER})
            cell.addView(darkLabel(name, 10f, selected==name, if(selected==name) orange else coolWhite).apply {gravity=Gravity.CENTER})
            bar.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(bar, LinearLayout.LayoutParams(-1,-2).apply {setMargins(sp(7),sp(6),sp(7),sp(8))})
    }

    private fun goToMaps(o: Incident) {
        if (!has(Rights.MAP)) return toast("Sem permissão para acessar o mapa")
        val address = listOf(o.rua, o.bairro, "Sapucaia do Sul RS").filter { it.isNotBlank() }.joinToString(", ")
        val uri = Uri.parse("geo:0,0?q=${Uri.encode(address)}")
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        catch (_: Exception) { toast("Não há aplicativo de mapas disponível") }
    }

    private fun profileScreen() {
        val u=currentUser ?:return showLogin()
        val body=screen("Minha área", "${u.name} • ${u.matricula}",::dashboard)
        val info=section(body,"Meu acesso")
        info.addView(label("Perfil: ${u.role}\nMatrícula: ${u.matricula}",14f))
        info.addView(label("Permissões disponíveis: ${Rights.custom.filter { has(it) }.joinToString { Rights.labels[it] ?: it }}",12f))
        info.addView(button("Alterar minha senha", false) { changePasswordAtLogin(u) })
        info.addView(button("Sair da conta",false) {showLogin()})
    }

    private fun reportsScreen() {
        if (!has(Rights.REPORTS)) return toast("Sem permissão para relatórios")
        val body=screen("Relatórios operacionais", "Dados armazenados neste aparelho", ::dashboard)
        val cases=store.incidents()
        val section=section(body,"Resumo de atendimentos")
        section.addView(label("Abertas: ${cases.count { it.status=="Aguardando atendimento" }}\nEm andamento: ${cases.count { it.status !in setOf("Aguardando atendimento","Atendida") }}\nConcluídas: ${cases.count { it.status=="Atendida" }}",15f))
        cases.filter { it.status=="Atendida" }.forEach { o ->
            val c=section(body,"${o.number} • ${o.tipo}")
            c.addView(label(o.rua,13f))
            c.addView(button("Abrir documentos",false){openIncident(o.id)})
        }
    }

    private fun caseListScreen() {
        val user=currentUser?:return showLogin()
        val root=vertical().apply {setBackgroundColor(darkBg)}
        val scroll=ScrollView(this).apply {isFillViewport=true}
        val content=vertical().apply {setPadding(sp(13),sp(15),sp(13),sp(12))}
        scroll.addView(content)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        content.addView(darkLabel("SENTINELA  /  SIGOPE",13f,true,orange))
        content.addView(darkLabel("Todas as ocorrências",23f,true))
        content.addView(darkLabel("Registros deste aparelho • ${user.name}",12f,false,Color.rgb(176,198,220)))
        val cases=store.incidents()
        val search=EditText(this).apply {
            hint="Filtrar número, rua, natureza..."
            setSingleLine(true);textSize=14f;setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(177,193,216));setPadding(sp(12),sp(10),sp(12),sp(10))
            background=darkShape()
        }
        content.addView(search,LinearLayout.LayoutParams(-1,-2).apply {topMargin=sp(12);bottomMargin=sp(12)})
        val list=vertical();content.addView(list)
        fun render(){
            list.removeAllViews()
            val q=search.text.toString().trim().lowercase()
            val selected=cases.filter { q.isBlank() || listOf(it.number,it.rua,it.tipo,it.bairro).any{v->v.lowercase().contains(q)} }
            if(selected.isEmpty())list.addView(darkLabel("Nenhuma ocorrência encontrada neste aparelho.",13f))
            selected.forEach{list.addView(dashboardCaseCard(it),LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=sp(8)})}
        }
        search.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){render()}
            override fun afterTextChanged(s:Editable?){}
        })
        render()
        addBottomNav(root,"Ocorrências")
    }

    private fun myReportsScreen(){
        val user=currentUser?:return showLogin()
        val body=screen("Meus atendimentos", "Atendimentos aceitos por ${user.name}",::dashboard)
        val accepted=store.incidents().filter{store.hasAccepted(it.id,user.id)}
        if(accepted.isEmpty())body.addView(label("Você ainda não aceitou nenhum atendimento neste aparelho.",14f))
        accepted.forEach {o->
            val c=section(body,"${o.number} • ${o.status}")
            c.addView(label("${o.tipo}\n${o.rua}",13f))
            c.addView(button("Abrir ocorrência",false){openIncident(o.id)})
        }
    }

    private fun showBootstrap() {
        val body = screen("Primeiro acesso", "Cadastre o administrador deste aparelho")
        val c = section(body, "Cadastro inicial seguro")
        c.addView(label("Senha: mínimo de 8 caracteres, com letra maiúscula, minúscula, número e símbolo.", 13f))
        c.addView(label("Matrícula inicial sugerida: 93866. Defina sua senha diretamente aqui; ela não está embutida no aplicativo.", 12f))
        val nome = field(c, "Nome do administrador", value = "Cleber Iantzen")
        val mat = field(c, "Matrícula", value = "93866")
        val senha = field(c, "Senha de acesso", password = true)
        val confirmacao = field(c, "Confirmar senha", password = true)
        confirmacao.imeOptions = EditorInfo.IME_ACTION_DONE
        c.addView(button("Criar administrador") {
            if (senha.text.toString() != confirmacao.text.toString()) return@button toast("As senhas não coincidem")
            try {
                val u = store.createUser(nome.text.toString(), mat.text.toString(), "ADMIN", senha.text.toString())
                currentUser = u
                val code = store.issueRecoveryCode(u.id, u.id)
                showRecoveryCode(code) { dashboard() }
            } catch (e: Exception) { toast(e.message ?: "Falha ao cadastrar") }
        })
        if (isTestBuild()) {
            val demo = section(body, "Conta demonstrativa — somente testes")
            demo.addView(label("Para testar o aplicativo sem dados reais: matrícula 9999 e senha Boco@123. Não usar em atendimentos reais.", 12f))
            demo.addView(button("Criar usuário de teste 9999", false) {
                AlertDialog.Builder(this)
                    .setTitle("Ativar conta de teste?")
                    .setMessage("Cria um administrador local de demonstração com credenciais conhecidas. Nunca utilizar dados reais nesta versão.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Criar") { _, _ ->
                        try {
                            val u = store.createUser("Usuário de Teste", "9999", "ADMIN", "Boco@123")
                            currentUser = u; toast("Conta de teste criada"); dashboard()
                        } catch (e: Exception) { toast(e.message ?: "Não foi possível criar a conta") }
                    }.show()
            })
        }
    }

    private fun showLogin() {
        currentUser = null
        val body = screen("Acesso operacional", "SIGOPE e Boletim de Atendimento em um único aplicativo")
        body.addView(ImageView(this).apply {
            setImageResource(R.drawable.sentinela_app_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Sentinela COMPDEC"
        }, LinearLayout.LayoutParams(-1, sp(112)).apply { bottomMargin = sp(10) })
        val c = section(body, "Identificação")
        val mat = field(c, "Matrícula")
        val pass = field(c, "Senha", password = true)
        pass.imeOptions = EditorInfo.IME_ACTION_DONE
        c.addView(button("Entrar") {
            val matricula = mat.text.toString().trim()
            if (matricula.isBlank() || pass.text.isBlank()) return@button toast("Informe matrícula e senha")
            val user = store.login(matricula, pass.text.toString())
            if (user == null) toast("Acesso não autorizado. Confira a matrícula e a senha cadastradas neste aparelho.")
            else {
                currentUser = user
                if (user.mustChangePassword) changePasswordAtLogin(user) else dashboard()
            }
        })
        c.addView(button("Esqueci minha senha", false) { forgotPassword() })
        body.addView(label("Os usuários deste piloto são locais: uma conta de outro aparelho não acessa este. Caso não tenha um código de recuperação, procure o administrador.", 12f))
    }

    /** Código aleatório exibido somente uma vez. Não salvar senha nem código em relatórios ou logs. */
    private fun showRecoveryCode(code: String, onContinue: () -> Unit) {
        val message = label("Guarde este código em local seguro. Ele permite recuperar sua senha mesmo sem internet, uma única vez.\n\n$code\n\nDepois de utilizá-lo, será necessário solicitar outro ao administrador.", 17f, true)
        message.setTextIsSelectable(true)
        message.setPadding(sp(20), sp(15), sp(20), sp(15))
        AlertDialog.Builder(this).setTitle("Código de recuperação do SENTINELA")
            .setView(message).setCancelable(false)
            .setPositiveButton("Anotei e guardei o código") { _, _ -> onContinue() }.show()
    }

    private fun changePasswordAtLogin(user: User) {
        val body = screen("Trocar senha temporária", "Para proteger sua conta, defina uma senha pessoal.")
        val card = section(body, "Nova senha de ${user.matricula}")
        val password = field(card, "Nova senha", password = true)
        val confirm = field(card, "Confirmar nova senha", password = true)
        card.addView(button("Salvar senha e continuar") {
            if (password.text.toString() != confirm.text.toString()) return@button toast("As senhas não coincidem")
            try {
                store.changeOwnPassword(user.id, password.text.toString())
                currentUser = store.userByMatricula(user.matricula)
                toast("Senha atualizada. Acesso autorizado.")
                dashboard()
            } catch (e: Exception) {toast(e.message ?: "Não foi possível alterar senha")}
        })
        card.addView(button("Sair", false) {showLogin()})
    }

    private fun forgotPassword() {
        val body = screen("Esqueci minha senha", "Recuperação offline por código de uso único", ::showLogin)
        val card = section(body, "Redefinir senha")
        card.addView(label("Informe sua matrícula e o código entregue no cadastro ou pelo administrador. O código funciona uma única vez.", 13f))
        val mat = field(card, "Matrícula", value = "93866")
        val code = field(card, "Código de recuperação", "XXXX-XXXX-XXXX")
        val password = field(card, "Nova senha", password = true)
        val confirm = field(card, "Confirmar nova senha", password = true)
        card.addView(button("Redefinir senha") {
            if (password.text.toString() != confirm.text.toString()) return@button toast("As senhas não coincidem")
            try {
                if (store.recoverWithCode(mat.text.toString(), code.text.toString(), password.text.toString())) {
                    toast("Senha redefinida. Entre com sua matrícula e nova senha.")
                    showLogin()
                } else toast("Matrícula ou código de recuperação inválido ou já utilizado")
            } catch (e: Exception) {toast(e.message ?: "Não foi possível recuperar a senha")}
        })
        val info = section(body, "Não tenho código")
        info.addView(label("Peça ao administrador do SENTINELA que redefina sua senha ou emita novo código em Gestão de usuários. Sem servidor central, o aplicativo não envia e-mail ou SMS.", 13f))
        if (isTestBuild()) {
            info.addView(label("RECURSO EXCLUSIVO DO PILOTO: em aparelho com bloqueio de tela configurado, o proprietário pode autorizar recuperação local. Isso não verifica sua identidade institucional e será removido na versão de produção.", 12f))
            info.addView(button("Recuperar acesso deste aparelho (somente testes)",false) { confirmPilotDeviceOwner() })
        }
    }

    private fun confirmPilotDeviceOwner() {
        if (!isTestBuild()) return toast("Indisponível fora do piloto")
        val manager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!manager.isDeviceSecure) return toast("Configure PIN ou bloqueio de tela do Android; sem ele use o código de recuperação")
        val intent = manager.createConfirmDeviceCredentialIntent(
            "Recuperação local — SENTINELA TESTE", "Confirme o bloqueio do aparelho. Só use no seu dispositivo autorizado."
        ) ?: return toast("O Android não permite validação neste aparelho")
        pilotUnlockVerified = false
        startActivityForResult(intent, 104)
    }

    private fun pilotRecoveryScreen() {
        if (!isTestBuild() || !pilotUnlockVerified) return showLogin()
        pilotUnlockVerified = false // só uma operação por desbloqueio
        val body = screen("Recuperação de testes", "Confirmação do bloqueio do aparelho concluída", ::showLogin)
        val card = section(body, "Redefinir administrador local")
        card.addView(label("ATENÇÃO: somente piloto. A posse do celular não comprova vínculo com a Defesa Civil. Não utilizar com atendimentos reais.", 13f))
        val admins = store.users().filter { it.role == "ADMIN" && it.active }
        if (admins.isEmpty()) {
            card.addView(label("Não existe administrador ativo. Procure suporte técnico para recuperar a base local.", 13f))
            return
        }
        val names = admins.map { "${it.name} • ${it.matricula}" }
        val choose = Spinner(this).apply {adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names)}
        card.addView(label("Administrador", 13f, true));card.addView(choose)
        val pass = field(card, "Nova senha", password = true)
        val confirm = field(card, "Confirmar nova senha", password = true)
        card.addView(button("Redefinir acesso de teste") {
            if (pass.text.toString() != confirm.text.toString()) return@button toast("As senhas não coincidem")
            try {
                val admin = admins[choose.selectedItemPosition]
                store.changeOwnPassword(admin.id, pass.text.toString())
                toast("Senha redefinida. Entre novamente.")
                showLogin()
            } catch (e: Exception) {toast(e.message ?: "Falha ao redefinir")}
        })
    }

    /** Tela operacional: dados reais deste aparelho, visual inspirado no layout aprovado. */
    private fun dashboard() {
        val user = currentUser ?: return showLogin()
        if (!user.active) return showLogin()
        val root=vertical().apply { setBackgroundColor(darkBg) }
        val scroll=ScrollView(this).apply { isFillViewport=true; clipToPadding=false }
        val content=vertical()
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)

        val header=vertical().apply {
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(4,30,65),Color.rgb(14,57,98)))
            setPadding(sp(15),sp(14),sp(15),sp(7))
        }
        val brand=row()
        brand.addView(ImageView(this).apply {
            setImageResource(R.drawable.sentinela_app_icon)
            scaleType=ImageView.ScaleType.CENTER_CROP
            background=darkShape(Color.rgb(2,19,40),10,Color.rgb(0,112,206))
        }, LinearLayout.LayoutParams(sp(56),sp(56)).apply {rightMargin=sp(9)})
        val titles=vertical()
        titles.addView(darkLabel("SENTINELA",21f,true))
        titles.addView(darkLabel("DEFESA CIVIL",13.5f,true,orange))
        titles.addView(darkLabel("Sapucaia do Sul – RS",10.5f))
        brand.addView(titles,LinearLayout.LayoutParams(0,-2,1f))
        brand.addView(ImageView(this).apply {
            setImageResource(R.drawable.defesa_civil_logo)
            scaleType=ImageView.ScaleType.FIT_CENTER
            contentDescription="Defesa Civil de Sapucaia do Sul"
        }, LinearLayout.LayoutParams(sp(56),sp(56)))
        header.addView(brand)
        val banner=ImageView(this).apply {
            setImageResource(R.drawable.equipe_banner)
            scaleType=ImageView.ScaleType.CENTER_CROP
            contentDescription="Equipe da Defesa Civil de Sapucaia do Sul"
            background=darkShape()
        }
        header.addView(banner, LinearLayout.LayoutParams(-1,sp(105)).apply {topMargin=sp(11)})
        val person=row().apply {setPadding(0,sp(8),0,0)}
        val personal=vertical()
        personal.addView(darkLabel("Olá, ${user.name}",16.5f,true))
        val nameRole=mapOf("ADMIN" to "Administrador", "OPERADOR" to "Operador", "AGENTE" to "Agente de campo", "CONSULTA" to "Visualização")
        personal.addView(darkLabel("Matrícula: ${user.matricula}  •  ${nameRole[user.role] ?: user.role}",11f))
        person.addView(personal,LinearLayout.LayoutParams(0,-2,1f))
        val connected=(getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetworkInfo?.isConnected==true
        person.addView(darkPill(if (connected) "● Online" else "◌ Offline",if(connected)Color.rgb(14,105,61) else Color.rgb(100,73,19)))
        header.addView(person)
        content.addView(header)
        val stripe=View(this).apply {setBackgroundColor(orange)}
        content.addView(stripe,LinearLayout.LayoutParams(-1,sp(3)))

        val area=vertical().apply {setPadding(sp(12),sp(10),sp(12),sp(12))}
        content.addView(area)
        val module=row()
        module.addView(darkLabel("SIGOPE",22f,true,orange))
        module.addView(darkLabel("  |  Gestão de Ocorrências",14f,true),LinearLayout.LayoutParams(0,-2,1f))
        area.addView(module)
        area.addView(darkLabel("Ocorrências locais e recebidas pelo HUB • Sincronização sob autenticação",11f,false,Color.rgb(175,196,219)))
        area.addView(darkLabel("HUB: " + (prefs.getString("hub_last_status","Não testado") ?: "Não testado"),11f,false,Color.rgb(167,192,223)))
        val cases=store.incidents()
        val pending=cases.count { it.status=="Aguardando atendimento" }
        val progress=cases.count { it.status!="Aguardando atendimento" && it.status!="Atendida" }
        val complete=cases.count { it.status=="Atendida" }
        val monthly=cases.count { it.date.startsWith(YearMonth.now().toString()) }
        val kpis=row().apply {setPadding(0,sp(9),0,0)}
        val a=dashboardStat("Pendentes",pending,Color.rgb(255,110,109))
        val b=dashboardStat("Em andamento",progress,Color.rgb(91,175,255))
        kpis.addView(a,LinearLayout.LayoutParams(0,-2,1f).apply {rightMargin=sp(8)})
        kpis.addView(b,LinearLayout.LayoutParams(0,-2,1f))
        area.addView(kpis)
        val kpis2=row().apply {setPadding(0,sp(8),0,sp(10))}
        kpis2.addView(dashboardStat("Concluídas",complete,Color.rgb(87,207,137)),LinearLayout.LayoutParams(0,-2,1f).apply {rightMargin=sp(8)})
        kpis2.addView(dashboardStat("Total do mês",monthly,Color.rgb(205,220,238)),LinearLayout.LayoutParams(0,-2,1f))
        area.addView(kpis2)

        val quicks=mutableListOf<Pair<String,()->Unit>>()
        if(has(Rights.CREATE)) quicks.add("+ Registrar ocorrência" to {newIncident()})
        quicks.add("↻ Sincronizar agora" to { SyncJobService.now(this) { message -> toast(message); dashboard() } })
        if(has(Rights.REPORTS)) quicks.add("▥ Relatórios" to {reportsScreen()})
        if(has(Rights.USERS)) quicks.add("♙ Gestão de usuários" to {userManagement()})
        if(has(Rights.SETTINGS)) quicks.add("⚙ Configurações" to {settings()})
        if(quicks.isNotEmpty()) {
            area.addView(darkLabel("Acesso rápido",16f,true))
            quicks.chunked(2).forEach { chunk ->
                val line=row().apply {setPadding(0,sp(5),0,0)}
                chunk.forEachIndexed {idx, option ->
                    line.addView(darkButton(option.first,idx==0,option.second),LinearLayout.LayoutParams(0,-2,1f).apply {if(idx==0)rightMargin=sp(8)})
                }
                if(chunk.size==1)line.addView(View(this),LinearLayout.LayoutParams(0,sp(1),1f))
                area.addView(line)
            }
        }
        area.addView(darkLabel("Ocorrências (Todas)",17f,true).apply {setPadding(0,sp(16),0,sp(5))})
        val search=row().apply {background=darkShape();setPadding(sp(6),0,sp(6),0)}
        val filter=Spinner(this).apply {
            adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Todas", "Pendentes", "Em andamento", "Concluídas"))
        }
        val query=EditText(this).apply {
            hint="Buscar número, endereço ou descrição"
            setSingleLine(true)
            textSize=13f
            inputType=InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(172,198,224))
            setPadding(sp(10),sp(10),sp(4),sp(10))
            background=ColorDrawable(Color.TRANSPARENT)
        }
        search.addView(query,LinearLayout.LayoutParams(0,sp(45),1f))
        area.addView(search)
        area.addView(darkLabel("Filtrar",11f,false,Color.rgb(175,196,219)))
        val filterWrap=vertical().apply {background=shape(Color.rgb(222,231,243),8);setPadding(sp(5),0,sp(5),0)}
        filterWrap.addView(filter)
        area.addView(filterWrap,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=sp(8)})
        val listContainer=vertical()
        area.addView(listContainer)
        fun redraw() {
            listContainer.removeAllViews()
            val q=query.text.toString().trim().lowercase()
            val selected=filter.selectedItem?.toString()?:"Todas"
            val filtered=cases.filter { o ->
                (q.isBlank() || listOf(o.number,o.tipo,o.rua,o.bairro,o.solicitante,o.descricao).any{it.lowercase().contains(q)}) &&
                when(selected){
                    "Pendentes" -> o.status=="Aguardando atendimento"
                    "Em andamento" -> o.status !in setOf("Aguardando atendimento","Atendida")
                    "Concluídas" -> o.status=="Atendida"
                    else -> true
                }
            }
            if(filtered.isEmpty()) {
                val msg=if(cases.isEmpty()) "Nenhuma ocorrência neste aparelho. Para compartilhar ocorrências entre celulares, ainda precisamos ativar o servidor central." else "Nenhuma ocorrência encontrada para este filtro."
                listContainer.addView(darkLabel(msg,13f,false,Color.rgb(185,201,225)).apply {setPadding(sp(14),sp(16),sp(14),sp(16));background=darkShape()})
            } else filtered.forEach { listContainer.addView(dashboardCaseCard(it),LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=sp(8)}) }
        }
        query.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){redraw()}
            override fun afterTextChanged(s:Editable?) {}
        })
        filter.onItemSelectedListener=object: android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long){redraw()}
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?) {}
        }
        redraw()
        availableUpdate?.let { upd ->
            area.addView(darkButton("Nova versão ${upd.version} disponível • Atualizar") {showUpdateDialog(upd)})
        }
        area.addView(darkLabel("IAntzen Tecnologia — Soluções Inteligentes",11f,false,Color.rgb(169,187,214)).apply {gravity=Gravity.CENTER;setPadding(0,sp(14),0,sp(14))})
        addBottomNav(root,"Início")
    }

    private fun dashboardCaseCard(o: Incident): LinearLayout {
        val card=vertical().apply {
            background=darkShape(Color.rgb(21,48,80),14)
            setPadding(sp(12),sp(11),sp(12),sp(11))
            isClickable=true; isFocusable=true
            setOnClickListener { openIncident(o.id) }
        }
        val first=row()
        val desc=vertical()
        desc.addView(darkLabel(o.number,14f,true))
        desc.addView(darkLabel(o.tipo.ifBlank {"Ocorrência sem natureza informada"},13f,true))
        first.addView(desc,LinearLayout.LayoutParams(0,-2,1f))
        val severity=when(o.prioridade.lowercase()) {
            "urgente", "alta" -> Color.rgb(174,49,53)
            "normal", "média" -> Color.rgb(198,102,28)
            else -> Color.rgb(45,128,86)
        }
        first.addView(darkPill(o.prioridade,severity))
        card.addView(first)
        card.addView(darkLabel("⌖ ${o.rua} — ${o.bairro}",12f,false,Color.rgb(209,219,236)))
        card.addView(darkLabel("${o.date}   •   ${o.status}   •   ${o.syncState}",11f,false,Color.rgb(167,192,223)))
        val rowActions=row().apply {setPadding(0,sp(8),0,0)}
        val u=currentUser
        if(u != null && o.status!="Atendida" && has(Rights.ACCEPT)) {
            val accepted=store.hasAccepted(o.id,u.id)
            val action=if(accepted) "✓ Aceito • Continuar" else "Aceitar atendimento"
            rowActions.addView(darkButton(action,!accepted) {
                if(!accepted) confirmAccept(o.id) else openIncident(o.id)
            },LinearLayout.LayoutParams(0,-2,1f).apply {rightMargin=sp(6)})
        }
        rowActions.addView(darkButton("Detalhes  ›") {openIncident(o.id)},LinearLayout.LayoutParams(0,-2,1f))
        card.addView(rowActions)
        return card
    }

    private fun confirmAccept(id: String) {
        val o=store.incident(id)?:return dashboard()
        AlertDialog.Builder(this)
            .setTitle("Aceitar atendimento ${o.number}?")
            .setMessage("Seu nome e matrícula serão registrados no histórico. A equipe originalmente designada será preservada. Depois de aceitar, você poderá iniciar o deslocamento e o atendimento conforme suas permissões.")
            .setNegativeButton("Cancelar",null)
            .setPositiveButton("Aceitar") {_,_->
                try {store.acceptIncident(id,currentUser!!.id);SyncJobService.schedule(this);toast("Atendimento aceito");openIncident(id)}
                catch(e:Exception){toast(e.message?:"Não foi possível aceitar")}
            }.show()
    }

    private fun newIncident() {
        if (!canOpen()) return toast("Somente operador ou administrador abre ocorrências")
        val body = screen("Nova ocorrência", "Registro local no SIGOPE", ::dashboard)
        val c = section(body, "Dados iniciais")
        val cat = Spinner(this)
        val categories = arrayOf("OCORRENCIAS", "VISTORIAS", "APOIOS OPERACIONAIS", "INTERDIÇÕES")
        cat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)
        c.addView(label("Categoria", 13f, true)); c.addView(cat)
        val solicitante = field(c,"Solicitante")
        val telefone = field(c,"Telefone")
        val rua = field(c,"Endereço (rua e número)")
        val bairro = field(c,"Bairro")
        val tipo = field(c,"Natureza da ocorrência", "Ex.: Queda de árvore")
        val descricao = field(c,"Descrição inicial")
        val equipe = field(c,"Equipe designada", "Ex.: Equipe 01")
        val prioridade = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("Normal", "Baixa", "Alta", "Urgente"))
        }
        c.addView(label("Prioridade", 13f, true)); c.addView(prioridade)
        c.addView(button("Registrar ocorrência") {
            try {
                val item = store.createIncident(cat.selectedItem.toString(),solicitante.text.toString(),
                    telefone.text.toString(),rua.text.toString(),bairro.text.toString(),
                    tipo.text.toString(),descricao.text.toString(),equipe.text.toString(),
                    prioridade.selectedItem.toString(), currentUser!!.id)
                SyncJobService.schedule(this)
                toast("Ocorrência ${item.number} criada offline"); openIncident(item.id)
            } catch (e: Exception) { toast(e.message ?: "Erro ao registrar") }
        })
    }

    private fun openIncident(id: String) {
        val u=currentUser?:return showLogin()
        val o=store.incident(id)?:return dashboard()
        selectedIncidentId=id
        val body=screen("Ocorrência ${o.number}","${o.tipo} • ${o.status}",::dashboard)
        val c=section(body,"Informações da ocorrência")
        c.addView(label("${o.rua} — ${o.bairro}",17f,true))
        c.addView(label("Solicitante: ${o.solicitante}\nTelefone: ${o.telefone}\nNatureza: ${o.tipo}\nPrioridade: ${o.prioridade}\nEquipe designada: ${o.equipe.ifBlank { "Não informada" }}\n${o.descricao}",13f))
        if(has(Rights.MAP)) c.addView(button("Abrir endereço no mapa",false){goToMaps(o)})
        val participants=store.acceptanceNames(id)
        val stage=section(body,"Participação e desdobramentos")
        stage.addView(label("Agentes que aceitaram: ${if(participants.isEmpty()) "Nenhum" else participants.joinToString()}\nEquipe designada: ${o.equipe.ifBlank { "Não informada" }}",13f))
        val mine=store.hasAccepted(id,u.id)
        if(o.status!="Atendida" && !mine && has(Rights.ACCEPT)) stage.addView(button("Aceitar atendimento") {confirmAccept(id)})
        if(o.status!="Atendida" && (mine || u.role=="ADMIN")) {
            if(o.status=="Aguardando atendimento" && has(Rights.STATUS)) stage.addView(button("A caminho") {
                try {store.updateStatus(id,"Equipe deslocada",u.id);SyncJobService.schedule(this);openIncident(id)} catch(e:Exception){toast(e.message?:"Falha ao mudar status")}
            })
            if(o.status in setOf("Equipe deslocada","Aguardando apoio","Aguardando retorno") && has(Rights.STATUS)) stage.addView(button("Iniciar atendimento → Abrir BA") {
                try {store.updateStatus(id,"Em atendimento",u.id);SyncJobService.schedule(this);reportScreen(id)} catch(e:Exception){toast(e.message?:"Falha ao iniciar")}
            })
            if(o.status=="Em atendimento" && has(Rights.BA)) stage.addView(button("Continuar Boletim de Atendimento") {reportScreen(id)})
            if(o.status in setOf("Equipe deslocada","Em atendimento") && has(Rights.STATUS)) {
                stage.addView(button("Aguardando apoio",false){try{store.updateStatus(id,"Aguardando apoio",u.id);SyncJobService.schedule(this);openIncident(id)}catch(e:Exception){toast(e.message?:"Falha")}})
                stage.addView(button("Aguardando retorno",false){try{store.updateStatus(id,"Aguardando retorno",u.id);SyncJobService.schedule(this);openIncident(id)}catch(e:Exception){toast(e.message?:"Falha")}})
            }
        } else if(o.status!="Atendida") stage.addView(label("Para executar este atendimento, aceite a ocorrência e utilize as permissões concedidas pelo administrador.",13f))
        val docs=section(body,"Boletim, fotos e arquivamento")
        docs.addView(label("Boletim PDF: ${if(o.pdfPath.isBlank()) "Pendente" else "Gerado"}\nFotos: ${store.photos(id).size}\nSincronização: ${o.syncState}",13f))
        if(o.status=="Atendida") {
            if(has(Rights.BA)) docs.addView(button("Consultar BA",false){reportScreen(id)})
            if(has(Rights.EXPORT)) docs.addView(button("Exportar PDFs e fotografias",false){exportIncident(id)})
            if(has(Rights.SETTINGS) && o.syncState!="SINCRONIZADO") docs.addView(button("Solicitar sincronização",false){SyncJobService.schedule(this);toast("Envio solicitado, se o servidor estiver configurado")})
        }
        val trail=section(body,"Histórico de movimentações")
        if(has(Rights.EXPORT)) trail.addView(button("Gerar histórico PDF",false){
            try {val pdf=TimelinePdf.generate(this,o,store.auditEvents(id));toast("Histórico salvo: ${pdf.name}")}
            catch(e:Exception){toast(e.message?:"Falha ao gerar o histórico")}
        })
        store.auditEvents(id).forEach { event ->
            trail.addView(label("${event.at} • ${event.actorName} (${event.actorMatricula})\n${event.action}",12f))
        }
    }

    private fun reportScreen(id: String) {
        val o = store.incident(id) ?: return dashboard()
        val old = store.report(id)
        val readonly = o.status == "Atendida" || !store.canOperate(id,currentUser!!.id,Rights.BA)
        val body = screen("Boletim de Atendimento", "${o.number} • ${o.rua}", { openIncident(id) })
        val c = section(body, "Dados do atendimento")
        c.addView(label("Solicitante: ${o.solicitante}\nLocal: ${o.rua} — ${o.bairro}\nNatureza: ${o.tipo}", 13f))
        val historico = field(c, "Histórico / constatações", value = old.historico)
        val providencias = field(c, "Providências adotadas", value = old.providencias)
        val responsavel = field(c, "Responsável presente no local",value=old.responsavel.ifBlank { o.solicitante })
        val doc = field(c, "Documento do responsável (quando informado)", value = old.responsavelDocumento)
        val outros = field(c, "Outros agentes (não assinam o BA)",value=old.outrosAgentes)
        val gps = field(c, "Localização / observações de GPS",value=old.gps)
        if(readonly) listOf(historico,providencias,responsavel,doc,outros,gps).forEach { it.isEnabled=false }
        val signer = section(body, "Primeiro agente — assinatura cadastrada")
        signer.addView(label("A assinatura cadastrada será recuperada pela matrícula somente após confirmar a senha do agente.",12f))
        val mat = field(signer,"Matrícula do primeiro agente",value=old.agenteMatricula)
        if(readonly) mat.isEnabled=false
        var agentSignature = old.agenteSignature
        var agentName = old.agenteNome
        val agentLabel = label("Agente: ${old.agenteNome.ifBlank { "não identificado" }} • ${if (old.agenteSignature.isNotBlank()) "assinatura confirmada" else "sem assinatura"}", 12f)
        signer.addView(agentLabel)
        if (!readonly) signer.addView(button("Recuperar e confirmar assinatura",false) {
            val u = store.userByMatricula(mat.text.toString())
            if (u == null || u.signaturePath.isBlank()) return@button toast("Matrícula não cadastrada ou assinatura ausente")
            val pass = EditText(this).apply {
                hint = "Senha do agente ${u.name}"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            AlertDialog.Builder(this).setTitle("Confirmar assinatura de ${u.name}")
                .setMessage("O agente deve digitar sua própria senha. O registro ficará vinculado ao BA ${o.number}.")
                .setView(pass)
                .setNegativeButton("Cancelar",null)
                .setPositiveButton("Confirmar") {_,_->
                    if (store.login(u.matricula,pass.text.toString())?.id != u.id) toast("Confirmação inválida")
                    else {
                        val snapshot = File(FileTools.dir(this,id),"assinatura_primeiro_agente.png")
                        File(u.signaturePath).copyTo(snapshot, overwrite = true)
                        agentSignature = snapshot.absolutePath; agentName = u.name
                        agentLabel.text = "Agente: ${u.name} • assinatura confirmada"
                        toast("Assinatura vinculada ao atendimento")
                    }
                }.show()
        })
        var responsibleSignature = old.responsavelSignature
        val resident = section(body, "Assinatura do responsável no local")
        val residentLabel = label(if (responsibleSignature.isBlank()) "Pendente" else "Assinatura registrada",12f)
        resident.addView(residentLabel)
        if (!readonly) resident.addView(button("Coletar assinatura no celular",false) {
            collectSignature("Assinatura do responsável", File(FileTools.dir(this,id),"assinatura_responsavel.png")) { path ->
                responsibleSignature = path
                residentLabel.text = "Assinatura registrada"
            }
        })
        fun report(): Report = Report(id, historico.text.toString().trim(), providencias.text.toString().trim(),
            responsavel.text.toString().trim(),doc.text.toString().trim(),mat.text.toString().trim(),
            agentName,agentSignature,responsibleSignature,outros.text.toString().trim(),gps.text.toString().trim(), LocalStore.now())
        val photosBlock = section(body, "Registro fotográfico")
        val photoCounter = label("${store.photos(id).size} fotografia(s) armazenada(s) neste aparelho", 13f)
        photosBlock.addView(photoCounter)
        if (!readonly && has(Rights.PHOTOS)) {
            photosBlock.addView(button("Tirar fotografia") { saveDraft(report()); takePhoto(id) })
            photosBlock.addView(button("Selecionar da galeria",false) { saveDraft(report()); choosePhoto(id) })
        }
        store.photos(id).forEachIndexed { ix, _ -> photosBlock.addView(label("Foto ${ix+1} — salva localmente", 12f)) }
        val actions = section(body, "Gravação e conclusão")
        if (!readonly) {
            actions.addView(button("Salvar rascunho", false) { saveDraft(report()); toast("Rascunho salvo no aparelho") })
            if(has(Rights.FINISH)) actions.addView(button("Salvar BA e finalizar ocorrência") {
                val r = report()
                if (r.historico.isBlank() || r.providencias.isBlank()) return@button toast("Preencha histórico e providências")
                if (r.agenteSignature.isBlank() || !File(r.agenteSignature).exists()) return@button toast("Confirme a assinatura do primeiro agente")
                if (r.responsavelSignature.isBlank() || !File(r.responsavelSignature).exists()) return@button toast("Colete a assinatura do responsável")
                val photoList = store.photos(id)
                if (photoList.isEmpty()) return@button toast("Envie pelo menos uma fotografia antes de finalizar")
                try {
                    saveDraft(r)
                    val pdf = ReportPdf.generate(this, o, r, photoList)
                    store.conclude(id,pdf.absolutePath,currentUser!!.id)
                    val completed=store.incident(id) ?: error("Falha ao consultar a ocorrência concluída")
                    TimelinePdf.generate(this,completed,store.auditEvents(id))
                    FileTools.archive(this,completed,store.report(id),store.photos(id),store.auditEvents(id))
                    SyncJobService.schedule(this)
                    toast("BA e ocorrência concluídos localmente. Arquivamento remoto pendente.")
                    openIncident(id)
                } catch (e: Exception) { toast("Erro ao gerar BA: ${e.message}") }
            })
        } else actions.addView(label("Atendimento concluído. As alterações e novas assinaturas estão bloqueadas nesta versão.", 12f))
    }

    private fun saveDraft(r: Report) { store.saveReport(r, currentUser!!.id); SyncJobService.schedule(this) }

    private fun collectSignature(title: String, target: File, onSaved: (String)->Unit) {
        val holder = vertical().apply {setPadding(sp(12),sp(12),sp(12),sp(12))}
        val canvas = SignatureView(this)
        holder.addView(label("Assine na área branca abaixo com o dedo.",13f))
        holder.addView(canvas, LinearLayout.LayoutParams(-1,sp(220)))
        holder.addView(button("Limpar",false) {canvas.clear()})
        AlertDialog.Builder(this).setTitle(title).setView(holder)
            .setNegativeButton("Cancelar",null)
            .setPositiveButton("Salvar") {_,_->
                if (canvas.saveTo(target)) {onSaved(target.absolutePath); toast("Assinatura salva")}
                else toast("Assinatura vazia. Repita a coleta")
            }.show()
    }

    private fun takePhoto(id: String) {
        attachmentIncidentId = id
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,"SENTINELA_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/Sentinela")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)
        if (uri == null) return toast("Não foi possível iniciar a câmera")
        cameraUri = uri
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {putExtra(MediaStore.EXTRA_OUTPUT,uri); addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}
        try { startActivityForResult(intent,101) } catch (e: Exception) {toast("Câmera indisponível: ${e.message}")}
    }

    private fun choosePhoto(id: String) {
        attachmentIncidentId = id
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent,102)
    }

    @Deprecated("Compatibilidade com o fluxo mínimo do piloto")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 104) {
            if (resultCode == RESULT_OK && isTestBuild()) {
                pilotUnlockVerified = true
                pilotRecoveryScreen()
            } else toast("Autorização do aparelho não confirmada")
            return
        }
        val id = attachmentIncidentId ?: return
        if (resultCode != RESULT_OK) { cameraUri = null; return }
        if (requestCode == 103) { data?.data?.let { writeExport(it, id) }; return }
        val uri = if (requestCode==101) cameraUri else data?.data
        cameraUri = null
        if (uri != null) {
            try {
                val (compressed, original) = FileTools.importPhoto(this,id,uri)
                store.addPhoto(id,compressed.absolutePath,original.absolutePath,currentUser!!.id)
                toast("Foto salva localmente. Cópia original preservada.")
            } catch (e: Exception) { toast("Não foi possível importar imagem: ${e.message}") }
        }
        reportScreen(id)
    }

    private fun userManagement() {
        if (currentUser?.role != "ADMIN") return dashboard()
        val body = screen("Gestão de usuários", "Edição, perfis, senhas e assinaturas", ::dashboard)
        val intro = section(body, "Contas deste aparelho")
        intro.addView(label("Apenas o administrador pode editar cadastros ou redefinir senhas. As alterações são locais e não afetam outros celulares.", 12f))
        store.users().forEach { u ->
            val status = if (u.active) "ATIVO" else "INATIVO"
            val c = section(body,"${u.name} • ${u.matricula}")
            c.addView(label("Perfil: ${u.role} • $status • Assinatura: ${if(u.signaturePath.isBlank()) "pendente" else "cadastrada"}",12f))
            c.addView(button("Editar cadastro e permissões",false) { editUserScreen(u.id) })
            c.addView(button("Redefinir senha",false) { resetUserPasswordScreen(u.id) })
            c.addView(button("Gerar novo código de recuperação",false) {
                AlertDialog.Builder(this).setTitle("Substituir código de recuperação?")
                    .setMessage("O código anterior deixará de funcionar. Entregue o novo código apenas ao titular desta conta.")
                    .setNegativeButton("Cancelar",null)
                    .setPositiveButton("Gerar") {_,_->
                        try {showRecoveryCode(store.issueRecoveryCode(u.id, currentUser!!.id)) {userManagement()}}
                        catch (e: Exception) {toast(e.message ?: "Falha ao gerar código")}
                    }.show()
            })
            if (u.id != currentUser!!.id) c.addView(button("Excluir acesso (preservar histórico)",false) {
                AlertDialog.Builder(this).setTitle("Excluir acesso de ${u.name}?")
                    .setMessage("O usuário não poderá entrar novamente. Seus boletins e histórico permanecem arquivados. Esta ação não apaga ocorrências.")
                    .setNegativeButton("Cancelar",null)
                    .setPositiveButton("Excluir") {_,_->
                        try {store.deleteUser(u.id,currentUser!!.id);toast("Acesso excluído");userManagement()}
                        catch(e:Exception){toast(e.message?:"Não foi possível excluir")}
                    }.show()
            })
            if (u.active) c.addView(button("Cadastrar / atualizar assinatura",false) {
                val dest = File(filesDir,"assinaturas/${u.id}.png")
                collectSignature("Assinatura cadastrada de ${u.name}",dest) { path ->
                    store.saveRegisteredSignature(u.id,path); userManagement()
                }
            })
        }
        val c = section(body,"Novo usuário")
        val name = field(c,"Nome")
        val mat = field(c,"Matrícula")
        val password = field(c,"Senha inicial",password=true)
        val confirmPassword = field(c,"Confirmar senha",password=true)
        c.addView(label("Mínimo: 8 caracteres com maiúscula, minúscula, número e símbolo. A troca será exigida no primeiro login.", 12f))
        val roles = arrayOf("AGENTE", "OPERADOR", "CONSULTA", "ADMIN")
        val role = Spinner(this).apply {adapter = ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,roles)}
        c.addView(label("Função",13f,true));c.addView(role)
        c.addView(label("Permissões individuais (além de consultar todas as ocorrências)",13f,true))
        val rightsBoxes = mutableMapOf<String, CheckBox>()
        Rights.custom.forEach { right ->
            val cb=CheckBox(this).apply { text=Rights.labels[right] ?: right; setTextColor(ink); isChecked=right in Rights.defaults("AGENTE") }
            c.addView(cb);rightsBoxes[right]=cb
        }
        role.onItemSelectedListener=object: android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long) {
                val defaults=Rights.defaults(role.selectedItem.toString())
                rightsBoxes.forEach{(right,checkbox)->checkbox.isChecked=right in defaults;checkbox.isEnabled=role.selectedItem.toString()!="ADMIN"}
            }
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?) {}
        }
        c.addView(button("Cadastrar usuário") {
            if (password.text.toString() != confirmPassword.text.toString()) return@button toast("As senhas não coincidem")
            try {
                val u = store.createUser(name.text.toString(),mat.text.toString(),role.selectedItem.toString(),password.text.toString())
                store.requirePasswordChange(u.id, currentUser!!.id)
                store.setUserRights(u.id,rightsBoxes.filterValues { it.isChecked }.keys,currentUser!!.id)
                toast("Usuário cadastrado: ${u.matricula}")
                userManagement()
            } catch (e: Exception) {toast(e.message ?: "Falha no cadastro")}
        })
    }

    private fun editUserScreen(userId: String) {
        val me = currentUser ?: return showLogin()
        if (me.role != "ADMIN") return dashboard()
        val u = store.userById(userId) ?: return userManagement()
        val body = screen("Editar usuário", u.matricula, ::userManagement)
        val c = section(body,"Dados e permissões")
        val name = field(c,"Nome completo",value=u.name)
        val mat = field(c,"Matrícula",value=u.matricula)
        val roles = arrayOf("AGENTE", "OPERADOR", "CONSULTA", "ADMIN")
        val role = Spinner(this).apply {adapter = ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,roles)}
        role.setSelection(roles.indexOf(u.role).coerceAtLeast(0))
        c.addView(label("Função",13f,true));c.addView(role)
        val active = CheckBox(this).apply {text = "Conta ativa";isChecked=u.active;setTextColor(ink)}
        c.addView(active)
        c.addView(label("Permissões individuais • a lista geral é visível para todos",13f,true))
        val rightBoxes=mutableMapOf<String,CheckBox>()
        Rights.custom.forEach { right ->
            val cb=CheckBox(this).apply {
                text=Rights.labels[right] ?: right
                setTextColor(ink)
                isChecked=right in store.rightsForUser(u.id)
                isEnabled=u.role!="ADMIN"
            }
            c.addView(cb);rightBoxes[right]=cb
        }
        role.onItemSelectedListener=object: android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long){
                val admin=role.selectedItem.toString()=="ADMIN"
                rightBoxes.values.forEach { it.isEnabled=!admin;if(admin)it.isChecked=true }
            }
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?) {}
        }
        c.addView(button("Salvar alterações") {
            try {
                store.updateUser(u.id,name.text.toString(),mat.text.toString(),role.selectedItem.toString(),active.isChecked,me.id)
                store.setUserRights(u.id,rightBoxes.filterValues { it.isChecked }.keys,me.id)
                toast("Cadastro e permissões atualizados"); userManagement()
            } catch (e: Exception) {toast(e.message ?: "Não foi possível atualizar")}
        })
    }

    private fun resetUserPasswordScreen(userId: String) {
        val admin = currentUser ?: return showLogin()
        if (admin.role != "ADMIN") return dashboard()
        val target = store.userById(userId) ?: return userManagement()
        val body = screen("Redefinir senha", "${target.name} • ${target.matricula}", ::userManagement)
        val c = section(body,"Nova credencial")
        c.addView(label("A senha anterior não será exibida. Informe uma nova senha e repasse-a diretamente ao usuário. Ele deverá trocá-la ao entrar.", 13f))
        val pass = field(c,"Nova senha",password=true)
        val confirm = field(c,"Confirmar nova senha",password=true)
        c.addView(button("Confirmar redefinição") {
            if (pass.text.toString() != confirm.text.toString()) return@button toast("As senhas não coincidem")
            try {
                store.resetPasswordByAdmin(target.id,pass.text.toString(),admin.id)
                toast("Senha redefinida. Peça ao usuário que entre com a nova senha.")
                userManagement()
            } catch (e: Exception) {toast(e.message ?: "Não foi possível redefinir")}
        })
    }

    private fun settings() {
        if(!has(Rights.SETTINGS)) return toast("Sem permissão para configurações administrativas")
        val body = screen("Configurações", "Arquivamento e sincronização", ::dashboard)
        val updates = section(body,"Atualizações")
        updates.addView(label("Versão instalada: ${UpdateChecker.installedVersion(this)}", 13f, true))
        updates.addView(button("Verificar atualizações",false) {checkForUpdates(true)})
        updates.addView(label("A instalação de APK exige sua autorização. A atualização preserva dados apenas com assinatura digital compatível. Para os testes iniciais, exporte os boletins antes de reinstalar.",12f))
        val c = section(body,"Conexão com Sentinela HUB")
        c.addView(label("O HUB precisa estar instalado e acessível por HTTPS com certificado confiável. Use uma conta de operador criada no HUB. Não utilize caminho de pasta SMB nem HTTP.",13f))
        val url = field(c,"Endereço HTTPS do HUB", "https://hub.exemplo.br", prefs.getString("url","")?:"")
        val username = field(c,"Usuário do HUB", "Conta operador",prefs.getString("hub_user","")?:"")
        val secret = field(c,"Senha do HUB (deixe vazio para manter a anterior)",password=true)
        c.addView(button("Salvar configuração") {
            val address = url.text.toString().trim()
            if(!address.startsWith("https://") || address.contains("@") || runCatching { java.net.URL(address).path.trim('/').isNotBlank() }.getOrDefault(true)) return@button toast("Informe somente a URL raiz HTTPS do HUB")
            if(username.text.toString().trim().isBlank()) return@button toast("Informe o usuário do HUB")
            prefs.edit().putString("url",address.trimEnd('/')).putString("hub_user",username.text.toString().trim()).apply()
            if(secret.text.isNotEmpty()) SecureSecrets.save(this,secret.text.toString())
            SyncJobService.periodic(this)
            SyncJobService.schedule(this)
            toast("Configuração salva. Verifique o teste de sincronização.")
        })
        c.addView(button("Testar conexão e sincronizar",false) { SyncJobService.now(this) { toast(it); settings() } })
        c.addView(label("Conflitos aparecem com estado CONFLITO; não são sobrescritos automaticamente. Acesso externo exige HTTPS confiável.",12f))
        val warn = section(body,"Segurança e dados locais")
        warn.addView(label("Dados armazenados no espaço privado do aplicativo. Não limpe os dados nem desinstale antes de exportar e confirmar o arquivamento. Senhas possuem hash com salt. O piloto ainda não oferece criptografia de banco ou backup remoto completo.",13f))
        warn.addView(label("O aplicativo não usa o banco SIGOPE ou BA da Lovable.",13f,true))
    }

    /** Verifica em toda abertura. Sem rede, o atendimento offline continua normalmente. */
    private fun checkForUpdates(interactive: Boolean) {
        if (checkingUpdate) {
            if (interactive) toast("Verificação em andamento")
            return
        }
        checkingUpdate = true
        Thread {
            val result = runCatching { UpdateChecker.findNewerRelease(this) }
            runOnUiThread {
                checkingUpdate = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { release ->
                    if (release != null) {
                        availableUpdate = release
                        // Reexibir em cada nova abertura enquanto a versão estiver antiga.
                        showUpdateDialog(release)
                    } else if (interactive) toast("SENTINELA atualizado ou sem nova versão publicada")
                }.onFailure {
                    if (interactive) toast("Não foi possível verificar. Confira sua conexão.")
                }
            }
        }.start()
    }

    private fun showUpdateDialog(release: UpdateChecker.Release) {
        val urgency = if (release.required) "Atualização importante" else "Nova versão disponível"
        AlertDialog.Builder(this)
            .setTitle("$urgency • SENTINELA ${release.version}")
            .setMessage("Instalada: ${UpdateChecker.installedVersion(this)}\nDisponível: ${release.version}\n\n${release.notes.take(650)}\n\nA atualização requer autorização do Android e deve preservar os dados quando a assinatura for compatível. Se houver registros pendentes, exporte-os antes de qualquer reinstalação.")
            .setNegativeButton("Depois", null)
            .setPositiveButton("Atualizar agora") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl)))
                } catch (_: Exception) {
                    toast("Não foi possível abrir o navegador para baixar a atualização")
                }
            }.show()
    }

    private fun exportIncident(id: String) {
        val o = store.incident(id) ?: return
        try {
            val zip = FileTools.archive(this,o,store.report(id),store.photos(id),store.auditEvents(id))
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"; putExtra(Intent.EXTRA_TITLE,zip.name)
            }
            attachmentIncidentId = id
            startActivityForResult(intent,103)
        } catch (e: Exception) {toast("Falha ao preparar pacote: ${e.message}")}
    }

    private fun writeExport(target: Uri, incidentId: String) {
        val o=store.incident(incidentId)?:return
        val zip=FileTools.archive(this,o,store.report(incidentId),store.photos(incidentId),store.auditEvents(incidentId))
        contentResolver.openOutputStream(target)?.use { dest -> zip.inputStream().use {it.copyTo(dest)} }
        toast("Pacote exportado. Copie-o para o servidor quando autorizado.")
    }
}
