package br.gov.rs.sapucaiadosul.sentinela

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.app.job.JobScheduler
import android.content.ContentValues
import android.content.Intent
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.text.method.HideReturnsTransformationMethod
import android.view.inputmethod.EditorInfo
import android.view.WindowManager
import android.view.WindowInsets
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
import org.json.JSONArray
import org.json.JSONObject

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

    /** Enquanto a tela esta aberta: consultar alteracoes sem depender do JobScheduler. */
    private val liveHandler = Handler(Looper.getMainLooper())
    private var foregroundSyncStarted = false
    private var foregroundSyncInFlight = false
    private var refreshVisibleScreen: (() -> Unit)? = null
    private var flushCurrentDraft: (() -> Unit)? = null
    private val autosaveHandler=Handler(Looper.getMainLooper())
    private val autosaveRunnable=Runnable { flushCurrentDraft?.invoke() }
    private fun persistCurrentDraft() {
        autosaveHandler.removeCallbacks(autosaveRunnable)
        flushCurrentDraft?.invoke()
        flushCurrentDraft=null
    }
    private fun autosaveSoon() {
        autosaveHandler.removeCallbacks(autosaveRunnable)
        autosaveHandler.postDelayed(autosaveRunnable,900L)
    }
    private fun watchForAutosave(vararg fields: EditText) {
        fields.forEach { input ->
            input.addTextChangedListener(object: TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {autosaveSoon()}
                override fun afterTextChanged(s:Editable?) {}
            })
        }
    }
    private fun addSelector(parent: LinearLayout, title: String, options: List<String>, value: String): Spinner {
        parent.addView(label(title,13f,true))
        val selector=Spinner(this)
        val entries=listOf("Não informado")+options
        selector.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,entries)
        val idx=options.indexOf(value)
        selector.setSelection(if(idx<0) 0 else idx+1)
        parent.addView(selector)
        return selector
    }
    private fun selectorValue(s:Spinner):String =
        s.selectedItem?.toString()?.takeUnless{it=="Não informado"} ?: ""
    private fun addTypeChooser(parent:LinearLayout, selected:MutableSet<String>):TextView {
        val control=label("",13f,true,navy).apply {
            background=shape(Color.rgb(235,241,249),10)
            setPadding(sp(12),sp(13),sp(12),sp(13))
        }
        fun refresh() {control.text="Tipos de ocorrência: "+(if(selected.isEmpty())"Selecionar..." else selected.joinToString("; "))+"   ▾"}
        refresh()
        control.setOnClickListener {
            val opts=OccurrenceCatalog.flat.toTypedArray()
            val flags=opts.map{it in selected}.toBooleanArray()
            AlertDialog.Builder(this).setTitle("Tipos de ocorrência (marque um ou mais)")
                .setMultiChoiceItems(opts,flags){_,index,checked->
                    if(checked)selected.add(opts[index]) else selected.remove(opts[index])
                }.setPositiveButton("Confirmar"){_,_->refresh();autosaveSoon()}
                .setNegativeButton("Cancelar",null).show()
        }
        parent.addView(control)
        return control
    }
    private val foregroundSyncTick = object : Runnable {
        override fun run() {
            if (!foregroundSyncStarted || currentUser == null) return
            if (foregroundSyncInFlight) return
            foregroundSyncInFlight = true
            SyncJobService.nowResult(applicationContext) { result, error ->
                foregroundSyncInFlight = false
                if (!foregroundSyncStarted || isFinishing || isDestroyed || currentUser == null) return@nowResult
                if (result != null && (result.received > 0 || result.sent > 0)) {
                    // Nunca desmontar formulario de atendimento em preenchimento.
                    // As telas de lista sao atualizadas automaticamente.
                    refreshVisibleScreen?.invoke()
                }
                // Sem internet/erro, espaçar tentativas para poupar bateria.
                liveHandler.removeCallbacks(this)
                liveHandler.postDelayed(this, if (error == null) 12_000L else 45_000L)
            }
        }
    }

    private fun beginForegroundSync() {
        if (foregroundSyncStarted || currentUser == null) return
        foregroundSyncStarted = true
        liveHandler.removeCallbacks(foregroundSyncTick)
        liveHandler.post(foregroundSyncTick)
    }

    private fun stopForegroundSync() {
        foregroundSyncStarted = false
        liveHandler.removeCallbacks(foregroundSyncTick)
    }

    private fun scheduleSync() {
        SyncJobService.schedule(this)
        // Ao alterar dados, tentar enviar imediatamente se o aplicativo estiver aberto.
        if (foregroundSyncStarted && !foregroundSyncInFlight) {
            liveHandler.removeCallbacks(foregroundSyncTick)
            liveHandler.postDelayed(foregroundSyncTick, 350L)
        }
    }

    override fun onResume() {
        super.onResume()
        beginForegroundSync()
    }

    override fun onPause() {
        persistCurrentDraft()
        stopForegroundSync()
        super.onPause()
    }

    private val navy = Color.rgb(20, 40, 73)
    private val orange = Color.rgb(242, 130, 37)
    private val bg = Color.rgb(246, 248, 251)
    private val ink = Color.rgb(28, 39, 58)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = navy
        window.navigationBarColor = navy
        store = LocalStore(this)
        HubConfig.ensure(this)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val savedAccount=prefs.getString("hub_user","").orEmpty()
        val savedSecret=SecureSecrets.read(this)
        val previous=if(savedAccount.isNotBlank() && savedSecret.isNotBlank() &&
            prefs.getBoolean("hub_verified_"+savedAccount,false))
            store.userByMatricula(savedAccount) else null
        if(previous!=null && previous.active) {
            currentUser=previous
            dashboard()
            beginForegroundSync()
        } else showLogin()
        if (prefs.contains("hub_user")) { SyncJobService.periodic(this); scheduleSync() }
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
        // Se o teclado abrir depois do foco, solicitar nova rolagem quando ele ja estiver visivel.
        input.setOnFocusChangeListener { view, focused ->
            if (focused) {
                view.postDelayed({
                    if (view.hasFocus()) {
                        view.requestRectangleOnScreen(
                            Rect(0, 0, view.width, view.height + sp(30)), true
                        )
                    }
                }, 280)
            }
        }
        return input
    }
    private fun section(content: LinearLayout, title: String): LinearLayout {
        val card = vertical().apply { background = shape(Color.WHITE, 16); setPadding(sp(15), sp(13), sp(15), sp(15)) }
        card.addView(label(title, 17f, true, navy))
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = sp(12) })
        return card
    }
    /**
     * Android 15+ pode desenhar atras do teclado apesar do adjustResize.
     * Reserva a area indicada por WindowInsets.Type.ime para que o ScrollView
     * leve o campo de texto em foco para uma posicao visivel.
     */
    private fun keyboardSafeScroll(scroll: ScrollView) {
        scroll.isFillViewport = true
        scroll.clipToPadding = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            scroll.setOnApplyWindowInsetsListener { view, insets ->
                val keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom
                val navigation = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                val safeBottom = maxOf(keyboard, navigation)
                if (view.paddingBottom != safeBottom) {
                    view.setPadding(view.paddingLeft, view.paddingTop,
                        view.paddingRight, safeBottom)
                }
                if (insets.isVisible(WindowInsets.Type.ime())) {
                    val focus = currentFocus
                    if (focus != null && focus !== scroll) {
                        focus.post {
                            if (focus.isFocused) focus.requestRectangleOnScreen(
                                Rect(0, 0, focus.width, focus.height + sp(32)), true
                            )
                        }
                    }
                }
                insets
            }
        }
        scroll.requestApplyInsets()
    }

    private fun screen(title: String, subtitle: String = "", back: (() -> Unit)? = null): LinearLayout {
        persistCurrentDraft()
        refreshVisibleScreen = null
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
        val scroll = ScrollView(this)
        scroll.addView(root)
        keyboardSafeScroll(scroll)
        setContentView(scroll)
        scroll.post { scroll.requestApplyInsets() }
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
        persistCurrentDraft()
        refreshVisibleScreen = null
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
        info.addView(button("Sair da conta",false) {logout()})
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
        persistCurrentDraft()
        refreshVisibleScreen = null
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
            val selected=store.incidents().filter { q.isBlank() || listOf(it.number,it.rua,it.tipo,it.bairro).any{v->v.lowercase().contains(q)} }
            if(selected.isEmpty())list.addView(darkLabel("Nenhuma ocorrência encontrada neste aparelho.",13f))
            selected.forEach{list.addView(dashboardCaseCard(it),LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=sp(8)})}
        }
        search.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){render()}
            override fun afterTextChanged(s:Editable?){}
        })
        render()
        refreshVisibleScreen = { render() }
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

    private fun logout() {
        persistCurrentDraft()
        stopForegroundSync()
        prefs.edit().remove("hub_user").commit()
        SecureSecrets.clear(this)
        showLogin()
    }
    private fun showLogin() {
        persistCurrentDraft()
        stopForegroundSync()
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
        mat.imeOptions = EditorInfo.IME_ACTION_NEXT
        pass.imeOptions = EditorInfo.IME_ACTION_DONE
        mat.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                pass.requestFocus()
                true
            } else false
        }
        c.addView(button("Entrar") {
            val matricula = mat.text.toString().trim()
            val senha = pass.text.toString()
            if (matricula.isBlank() || senha.isBlank()) return@button toast("Informe matrícula e senha")
            HubAuth.authenticate(this,matricula,senha) { identity, error ->
                if (identity != null) {
                    try {
                        val user = store.provisionFromHub(identity.name.ifBlank { matricula },
                            identity.matricula, identity.role, senha)
                        prefs.edit().putString("hub_user",identity.matricula)
                            .putBoolean("hub_verified_" + identity.matricula,true).apply()
                        SecureSecrets.save(this,senha)
                        currentUser=user
                        SyncJobService.periodic(this)
                        scheduleSync()
                        dashboard()
                        beginForegroundSync()
                    } catch(e:Exception) { toast("Erro ao preparar acesso: " + (e.message ?: "credencial inválida")) }
                } else if (error?.startsWith("HUB inacessível") == true) {
                    // O primeiro login precisa ser validado pelo HUB. Offline somente após isso.
                    val user = store.login(matricula,senha)
                    if (user != null && prefs.getBoolean("hub_verified_" + matricula,false)) {
                        currentUser=user
                        toast("Sem internet: trabalhando offline. O envio ocorrerá depois.")
                        dashboard()
                        beginForegroundSync()
                    } else toast("Primeiro acesso exige internet e conta ativa no HUB. " + error)
                } else toast(error ?: "Autenticação não autorizada no HUB")
            }
        })
        c.addView(button("Esqueci minha senha", false) { forgotPassword() })
        body.addView(label("O primeiro acesso valida sua conta com o HUB. Depois disso você poderá trabalhar offline; os dados serão enviados quando a internet retornar.", 12f))
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
        persistCurrentDraft()
        refreshVisibleScreen = null
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
        refreshVisibleScreen = { dashboard() }
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
                try {store.acceptIncident(id,currentUser!!.id);scheduleSync();toast("Atendimento aceito");openIncident(id)}
                catch(e:Exception){toast(e.message?:"Não foi possível aceitar")}
            }.show()
    }

    private fun newIncident() {
        if (!canOpen()) return toast("Somente operador ou administrador abre ocorrências")
        val body = screen("Nova ocorrência", "O registro será usado no BA, sem redigitação", ::dashboard)
        val c = section(body, "Dados do solicitante")
        val cat = Spinner(this)
        cat.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,
            arrayOf("OCORRENCIAS","VISTORIAS","APOIOS OPERACIONAIS","INTERDIÇÕES"))
        c.addView(label("Categoria",13f,true));c.addView(cat)
        val solicitante=field(c,"Solicitante")
        val cpf=field(c,"CPF do solicitante")
        val telefone=field(c,"Telefone")
        val rua=field(c,"Endereço (rua e número)")
        val bairro=field(c,"Bairro")
        val classification=section(body,"Tipo do solicitante e atendimento")
        val requester=addSelector(classification,"Tipo do solicitante",OccurrenceCatalog.requesterTypes,"")
        val attendance=addSelector(classification,"Modalidade de atendimento",OccurrenceCatalog.attendanceTypes,"")
        val nature=section(body,"Tipos de ocorrência")
        val selected=mutableSetOf<String>()
        addTypeChooser(nature,selected)
        val other=field(nature,"Outros (quando não constar na lista)")
        val initial=section(body,"Informações do acionamento")
        val descricao=field(initial,"Descrição inicial / relato do solicitante")
        val equipe=field(initial,"Equipe designada","Ex.: Equipe 01")
        val servidores=field(initial,"Servidores responsáveis (nome - matrícula, um por linha)")
        servidores.setSingleLine(false);servidores.minLines=2
        servidores.setText(currentUser?.let{"${it.name} - ${it.matricula}"}?:"")
        val prioridade=Spinner(this).apply{
            adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Normal","Baixa","Alta","Urgente"))
        }
        initial.addView(label("Prioridade",13f,true));initial.addView(prioridade)
        initial.addView(button("Registrar ocorrência") {
            try {
                val types=selected.toMutableList()
                if(other.text.toString().isNotBlank())types.add("Outros - "+other.text.toString().trim())
                if(types.isEmpty())return@button toast("Selecione um tipo de ocorrência")
                val item=store.createIncident(cat.selectedItem.toString(),
                    solicitante.text.toString(),telefone.text.toString(),rua.text.toString(),
                    bairro.text.toString(),types.joinToString("; "),
                    descricao.text.toString(),equipe.text.toString(),
                    prioridade.selectedItem.toString(),currentUser!!.id)
                val servers=JSONArray()
                servidores.text.toString().lines().filter{it.isNotBlank()}.forEach{line->
                    val parts=line.split(" - ",limit=2)
                    servers.put(JSONObject().put("nome",parts[0].trim())
                        .put("matricula",parts.getOrElse(1){""}.trim()))
                }
                store.saveBaMeta(item.id, BAExtra(
                    solicitanteCpf=cpf.text.toString().trim(),
                    tipoSolicitante=selectorValue(requester),
                    tipoAtendimento=selectorValue(attendance),
                    tiposOcorrencia=JSONArray(types).toString(),
                    servidores=servers.toString()
                ), currentUser!!.id)
                scheduleSync()
                toast("Ocorrência registrada no aparelho. O HUB criará a pasta ao receber o registro.")
                openIncident(item.id)
            } catch(e:Exception){toast(e.message?:"Erro ao registrar")}
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
                try {store.updateStatus(id,"Equipe deslocada",u.id);scheduleSync();openIncident(id)} catch(e:Exception){toast(e.message?:"Falha ao mudar status")}
            })
            if(o.status in setOf("Equipe deslocada","Aguardando apoio","Aguardando retorno") && has(Rights.STATUS)) stage.addView(button("Iniciar atendimento → Abrir BA") {
                try {store.updateStatus(id,"Em atendimento",u.id);scheduleSync();reportScreen(id)} catch(e:Exception){toast(e.message?:"Falha ao iniciar")}
            })
            if(o.status=="Em atendimento" && has(Rights.BA)) stage.addView(button("Continuar Boletim de Atendimento") {reportScreen(id)})
            if(o.status in setOf("Equipe deslocada","Em atendimento") && has(Rights.STATUS)) {
                stage.addView(button("Aguardando apoio",false){try{store.updateStatus(id,"Aguardando apoio",u.id);scheduleSync();openIncident(id)}catch(e:Exception){toast(e.message?:"Falha")}})
                stage.addView(button("Aguardando retorno",false){try{store.updateStatus(id,"Aguardando retorno",u.id);scheduleSync();openIncident(id)}catch(e:Exception){toast(e.message?:"Falha")}})
            }
        } else if(o.status!="Atendida") stage.addView(label("Para executar este atendimento, aceite a ocorrência e utilize as permissões concedidas pelo administrador.",13f))
        val docs=section(body,"Boletim, fotos e arquivamento")
        docs.addView(label("Boletim PDF: ${if(o.pdfPath.isBlank()) "Pendente" else "Gerado"}\nFotos: ${store.photos(id).size}\nSincronização: ${o.syncState}",13f))
        if(o.status=="Atendida") {
            if(has(Rights.BA)) docs.addView(button("Consultar BA",false){reportScreen(id)})
            if(has(Rights.EXPORT)) docs.addView(button("Exportar PDFs e fotografias",false){exportIncident(id)})
            if(has(Rights.SETTINGS) && o.syncState!="SINCRONIZADO") docs.addView(button("Solicitar sincronização",false){scheduleSync();toast("Envio solicitado, se o servidor estiver configurado")})
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
        val base=store.baMeta(id)
        val c=section(body,"Dados do solicitante — conferência")
        c.addView(label("Dados recebidos do operador. Corrija quando necessário.",12f))
        val sol=field(c,"Solicitante",value=o.solicitante)
        val solCpf=field(c,"CPF do solicitante",value=base.solicitanteCpf)
        val solPhone=field(c,"Telefone do solicitante",value=o.telefone)
        val address=field(c,"Endereço (rua e número)",value=o.rua)
        val district=field(c,"Bairro",value=o.bairro)
        val group=section(body,"Tipo do solicitante e atendimento")
        val requester=addSelector(group,"Tipo do solicitante",OccurrenceCatalog.requesterTypes,base.tipoSolicitante)
        val attendance=addSelector(group,"Modalidade de atendimento",OccurrenceCatalog.attendanceTypes,base.tipoAtendimento)
        val occurrenceType=section(body,"Tipos de ocorrência")
        val selectedTypes=mutableSetOf<String>()
        try {
            val array=JSONArray(base.tiposOcorrencia)
            for(ix in 0 until array.length())selectedTypes.add(array.optString(ix))
        } catch(_:Exception){selectedTypes.addAll(o.tipo.split("; ").filter{it.isNotBlank()})}
        addTypeChooser(occurrenceType,selectedTypes)
        val custom=field(occurrenceType,"Outros (descrição complementar)")
        val cReport=section(body,"Dados do atendimento")
        cReport.addView(label("Histórico inicial: ${o.descricao}",12f))
        val historico=field(cReport,"Histórico / constatações",
            value=old.historico.ifBlank{o.descricao})
        val providencias=field(cReport,"Providências adotadas",value=old.providencias)
        val contact=section(body,"Responsável no momento da vistoria")
        val responsavel=field(contact,"Nome do responsável",value=old.responsavel.ifBlank{o.solicitante})
        val doc=field(contact,"CPF do responsável",value=old.responsavelDocumento)
        val responsiblePhone=field(contact,"Telefone do responsável",value=base.responsavelTelefone)
        val relation=field(contact,"Vínculo com o solicitante",value=base.responsavelVinculo)
        val crew=section(body,"Servidores responsáveis")
        val staffArray=try{JSONArray(base.servidores)}catch(_:Exception){JSONArray()}
        val staffText=(0 until staffArray.length()).map { ix ->
            val ob=staffArray.optJSONObject(ix)
            (ob?.optString("nome")?:"")+" - "+(ob?.optString("matricula")?:"")
        }.joinToString("\n").ifBlank { currentUser?.let{"${it.name} - ${it.matricula}"}?:"" }
        val crewField=field(crew,"Nome - matrícula (um por linha)",value=staffText)
        crewField.setSingleLine(false);crewField.minLines=2
        val outros=field(crew,"Outros agentes presentes",value=old.outrosAgentes)
        val location=section(body,"Localização da ocorrência")
        val gps=field(location,"Localização / observações de GPS",value=old.gps)
        if(readonly) listOf(sol,solCpf,solPhone,address,district,historico,providencias,
            responsavel,doc,responsiblePhone,relation,crewField,outros,gps).forEach{it.isEnabled=false}
        if(readonly){requester.isEnabled=false;attendance.isEnabled=false;custom.isEnabled=false}
        // Assinatura manuscrita livre no atendimento, como a do responsavel.
        // A identidade do usuario que abriu o BA continua registrada na auditoria.
        val signer = section(body, "Assinatura do agente no local")
        signer.addView(label("O agente assina com o dedo na tela, sem precisar cadastrar a assinatura antes. Identifique quem esta assinando.",12f))
        val mat = field(signer,"Matrícula do agente",
            value=old.agenteMatricula.ifBlank { currentUser?.matricula.orEmpty() })
        val agentNameField = field(signer,"Nome do agente",
            value=old.agenteNome.ifBlank { currentUser?.name.orEmpty() })
        if(readonly) { mat.isEnabled=false;agentNameField.isEnabled=false }
        var agentSignature = old.agenteSignature
        val agentLabel = label(if (agentSignature.isNotBlank() && File(agentSignature).exists())
            "Assinatura manuscrita registrada" else "Assinatura pendente",12f)
        signer.addView(agentLabel)
        if (!readonly) signer.addView(button("Assinar na tela do celular",false) {
            if (mat.text.toString().trim().isEmpty() || agentNameField.text.toString().trim().isEmpty()) {
                return@button toast("Informe o nome e a matrícula do agente antes de assinar")
            }
            collectSignature("Assinatura de " + agentNameField.text.toString().trim(),
                File(FileTools.dir(this,id),"assinatura_primeiro_agente.png")) { path ->
                agentSignature = path
                agentLabel.text = "Assinatura registrada • " + agentNameField.text.toString().trim()
                autosaveSoon()
            }
        })
        var responsibleSignature = old.responsavelSignature
        val resident = section(body, "Assinatura do responsável no local")
        val residentLabel = label(if (responsibleSignature.isBlank()) "Pendente" else "Assinatura registrada",12f)
        resident.addView(residentLabel)
        if (!readonly) resident.addView(button("Coletar assinatura no celular",false) {
            collectSignature("Assinatura do responsável", File(FileTools.dir(this,id),"assinatura_responsavel.png")) { path ->
                responsibleSignature = path
                residentLabel.text = "Assinatura registrada"
                autosaveSoon()
            }
        })
        fun report(): Report=Report(id,historico.text.toString().trim(),providencias.text.toString().trim(),
            responsavel.text.toString().trim(),doc.text.toString().trim(),mat.text.toString().trim(),
            agentNameField.text.toString().trim(),agentSignature,responsibleSignature,
            outros.text.toString().trim(),gps.text.toString().trim(),LocalStore.now())
        if(!readonly) {
            flushCurrentDraft={
                try {
                    val types=selectedTypes.toMutableList()
                    if(custom.text.toString().isNotBlank())types.add("Outros - "+custom.text.toString().trim())
                    store.updateIncidentDetails(id,sol.text.toString(),solPhone.text.toString(),
                        address.text.toString(),district.text.toString(),types.joinToString("; "),
                        currentUser!!.id)
                    val array=JSONArray()
                    crewField.text.toString().lines().filter{it.isNotBlank()}.forEach{line->
                        val pieces=line.split(" - ",limit=2)
                        array.put(JSONObject().put("nome",pieces[0].trim())
                            .put("matricula",pieces.getOrElse(1){""}.trim()))
                    }
                    store.saveBaMeta(id,BAExtra(
                        solicitanteCpf=solCpf.text.toString().trim(),
                        tipoSolicitante=selectorValue(requester),
                        tipoAtendimento=selectorValue(attendance),
                        responsavelTelefone=responsiblePhone.text.toString().trim(),
                        responsavelVinculo=relation.text.toString().trim(),
                        tiposOcorrencia=JSONArray(types).toString(),
                        servidores=array.toString()
                    ),currentUser!!.id)
                    saveDraft(report())
                    prefs.edit().putLong("ba_autosave_"+id,System.currentTimeMillis()).apply()
                }catch(e:Exception){toast("Não foi possível salvar rascunho: "+(e.message?:"erro"))}
            }
            watchForAutosave(sol,solCpf,solPhone,address,district,historico,providencias,
                responsavel,doc,responsiblePhone,relation,crewField,outros,gps,mat,agentNameField,custom)
            val spinnerListener=object:android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,pos:Int,rowId:Long){autosaveSoon()}
                override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
            }
            requester.onItemSelectedListener=spinnerListener
            attendance.onItemSelectedListener=spinnerListener
        }
        val photosBlock = section(body, "Registro fotográfico")
        val photoCounter = label("${store.photos(id).size} fotografia(s) armazenada(s) neste aparelho", 13f)
        photosBlock.addView(photoCounter)
        if (!readonly && has(Rights.PHOTOS)) {
            photosBlock.addView(button("Tirar fotografia") { persistCurrentDraft(); takePhoto(id) })
            photosBlock.addView(button("Selecionar da galeria",false) { persistCurrentDraft(); choosePhoto(id) })
        }
        store.photos(id).forEachIndexed { ix, _ -> photosBlock.addView(label("Foto ${ix+1} — salva localmente", 12f)) }
        val actions = section(body, "Gravação e conclusão")
        if (!readonly) {
            actions.addView(label("Rascunho salvo automaticamente no aparelho após digitar. Sincronização com o HUB quando houver internet.",12f))
            actions.addView(button("Salvar rascunho",false) { persistCurrentDraft(); toast("Rascunho salvo no aparelho") })
            if(has(Rights.FINISH)) actions.addView(button("Salvar BA e finalizar ocorrência") {
                val r = report()
                if (r.historico.isBlank() || r.providencias.isBlank()) return@button toast("Preencha histórico e providências")
                if (r.agenteSignature.isBlank() || !File(r.agenteSignature).exists()) return@button toast("Confirme a assinatura do primeiro agente")
                if (r.responsavelSignature.isBlank() || !File(r.responsavelSignature).exists()) return@button toast("Colete a assinatura do responsável")
                val photoList = store.photos(id)
                if (photoList.isEmpty()) return@button toast("Envie pelo menos uma fotografia antes de finalizar")
                try {
                    persistCurrentDraft()
                    val pdf=ReportPdf.generate(this,store.incident(id)?:o,r,photoList)
                    store.conclude(id,pdf.absolutePath,currentUser!!.id)
                    val completed=store.incident(id) ?: error("Falha ao consultar a ocorrência concluída")
                    TimelinePdf.generate(this,completed,store.auditEvents(id))
                    FileTools.archive(this,completed,store.report(id),store.photos(id),store.auditEvents(id))
                    scheduleSync()
                    toast("BA e ocorrência concluídos localmente. Arquivamento remoto pendente.")
                    openIncident(id)
                } catch (e: Exception) { toast("Erro ao gerar BA: ${e.message}") }
            })
        } else actions.addView(label("Atendimento concluído. As alterações e novas assinaturas estão bloqueadas nesta versão.", 12f))
    }

    private fun saveDraft(r: Report) { store.saveReport(r, currentUser!!.id); scheduleSync() }

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
        val c = section(body,"Conexão automática com Sentinela HUB")
        c.addView(label("O endereço do HUB é incorporado ao aplicativo. O agente não precisa instalar VPN nem configurar servidor. A TI precisa habilitar a conexão HTTPS externa no SERVIDOR02.",13f))
        val url = field(c,"Endereço HTTPS do HUB (gerenciado pela TI)", value=HubConfig.URL).apply { isEnabled = false }
        val username = field(c,"Usuário do HUB",value=prefs.getString("hub_user","")?:"").apply { isEnabled=false }

        c.addView(label("Conectado como: " + (prefs.getString("hub_user","Aguardando login") ?: "Aguardando login"),12f))
        c.addView(button("Testar conexão e sincronizar",false) { SyncJobService.now(this) { toast(it); settings() } })
        c.addView(label("Falhas de conexão mantêm os registros no aparelho. Conflitos exigem conferência administrativa.",12f))
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
