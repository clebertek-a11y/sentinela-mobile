package br.gov.rs.sapucaiadosul.sentinela

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import java.util.Locale
import java.text.SimpleDateFormat
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
import org.json.JSONArray
import org.json.JSONObject
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

class MainActivity : Activity() {
    private lateinit var store: LocalStore
    private var currentUser: User? = null
    private var attachmentIncidentId: String? = null
    private var cameraUri: Uri? = null
    private var pendingGpsField:EditText?=null
    private fun fillGps(target:EditText){
        val fine=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
        val coarse=checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
        if(!fine && !coarse){
            pendingGpsField=target
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION),901)
            return
        }
        val manager=getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider=when{
            fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if(provider==null)return toast("Ative a localização do celular e tente novamente")
        fun applyLocation(location:Location?){
            if(location==null)return toast("Localização não disponível. Tente em área aberta.")
            val captured=SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale("pt","BR"))
                .format(java.util.Date(location.time))
            val result=String.format(Locale.US,"Lat: %.6f, Lon: %.6f",location.latitude,location.longitude) +
                "; Precisão: ${location.accuracy.toInt()} m; Capturada: $captured; Fonte: $provider"
            target.setText(result)
            scheduleDraftSave()
            toast(if(location.accuracy>100f)
                "Localização aproximada; confira antes de finalizar" else "Localização registrada")
        }
        try {
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.R)
                manager.getCurrentLocation(provider,null,mainExecutor){applyLocation(it)}
            else manager.requestSingleUpdate(provider,object:LocationListener {
                override fun onLocationChanged(location:Location){applyLocation(location)}
            },Looper.getMainLooper())
        } catch(e:Exception){toast("GPS indisponível: "+(e.message?:"erro"))}
    }

    override fun onRequestPermissionsResult(req:Int,permissions:Array<out String>,grants:IntArray) {
        super.onRequestPermissionsResult(req,permissions,grants)
        if(req==901) {
            val field=pendingGpsField
            pendingGpsField=null
            if(grants.any{it==PackageManager.PERMISSION_GRANTED}&&field!=null)fillGps(field)
            else toast("Para capturar a localização, autorize o GPS do Sentinela")
        }
    }
    private var selectedIncidentId: String? = null
    private var availableUpdate: UpdateChecker.Release? = null
    private var checkingUpdate = false
    private var pilotUnlockVerified = false
    private val prefs by lazy { getSharedPreferences("sync_settings", Context.MODE_PRIVATE) }

    private var realtime: HubRealtime? = null
    private var lastStaffRefresh=0L
    private fun refreshInstitutionalAccount() {
        val u=currentUser?:return
        if(System.currentTimeMillis()-lastStaffRefresh<600_000L)return
        lastStaffRefresh=System.currentTimeMillis()
        HubDirectory.refresh(applicationContext)
        HubSignature.sync(applicationContext,u.matricula)
    }
    private var activeDraftSaver: (() -> Unit)? = null
    private val draftHandler = Handler(Looper.getMainLooper())
    private var draftJob: Runnable? = null

    private fun scheduleDraftSave() {
        draftJob?.let { draftHandler.removeCallbacks(it) }
        val job = Runnable {
            try { activeDraftSaver?.invoke() }
            catch (e:Exception) { toast("Rascunho nao salvo: " + (e.message ?: "verifique os dados")) }
        }
        draftJob = job
        draftHandler.postDelayed(job, 1100L)
    }

    private fun flushDraft() {
        draftJob?.let { draftHandler.removeCallbacks(it) }
        draftJob = null
        val saver=activeDraftSaver
        activeDraftSaver=null
        try { saver?.invoke() } catch (_:Exception) { }
    }

    private fun watchDraft(inputs: List<EditText>, save: () -> Unit) {
        activeDraftSaver=save
        inputs.forEach { input ->
            input.addTextChangedListener(object:TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { scheduleDraftSave() }
                override fun afterTextChanged(s:Editable?) {}
            })
        }
    }

    private fun explicitLogout() {
        flushDraft()
        realtime?.stop();realtime=null
        stopForegroundSync()
        SyncJobService.cancel(this)
        prefs.edit().putBoolean("session_active",false).remove("hub_user").apply()
        SecureSecrets.clear(this)
        showLogin()
    }


    /** Enquanto a tela esta aberta: consultar alteracoes sem depender do JobScheduler. */
    private val liveHandler = Handler(Looper.getMainLooper())
    private var foregroundSyncStarted = false
    private var foregroundSyncInFlight = false
    private var refreshVisibleScreen: (() -> Unit)? = null
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
        refreshInstitutionalAccount()
        liveHandler.removeCallbacks(foregroundSyncTick)
        liveHandler.post(foregroundSyncTick)
        realtime=HubRealtime(applicationContext) { changed, revoked ->
            if(revoked) {
                toast("Acesso revogado ou senha alterada no HUB. Entre novamente.")
                prefs.edit().putBoolean("session_active",false).apply()
                showLogin()
            } else if(changed && foregroundSyncStarted && currentUser!=null) {
                liveHandler.removeCallbacks(foregroundSyncTick)
                liveHandler.post(foregroundSyncTick)
            }
        }.also { it.start() }
    }

    private fun stopForegroundSync() {
        foregroundSyncStarted = false
        realtime?.stop()
        realtime=null
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
        flushDraft()
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
        val remembered=prefs.getString("hub_user","").orEmpty()
        val restored=if (prefs.getBoolean("session_active",false) &&
            prefs.getBoolean("hub_verified_" + remembered,false) &&
            SecureSecrets.read(this).isNotBlank())
            store.userByMatricula(remembered) else null
        if(restored != null && restored.active) {
            currentUser=restored
            dashboard()
            SyncJobService.periodic(this)
            scheduleSync()
        } else showLogin()
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
    /**
     * Lista completa do BA com selecao multipla por grupo, sem depender de Lovable.
     * Grupos fechados economizam espaco na tela do agente.
     */
    private fun typeChecklist(parent: LinearLayout, selected: MutableSet<String>, readOnly: Boolean = false) {
        TipoOcorrenciaCatalogo.grupos.forEach { (group,types) ->
            val block=vertical()
            val title=button(group+"  ▾",false) {
                block.visibility=if(block.visibility==View.VISIBLE) View.GONE else View.VISIBLE
            }
            parent.addView(title)
            parent.addView(block)
            block.visibility=if(selected.any { it.startsWith(group+" - ") }) View.VISIBLE else View.GONE
            types.forEach { type ->
                val value=group+" - "+type
                block.addView(CheckBox(this).apply {
                    text=type;textSize=12.5f;setTextColor(ink)
                    isChecked=value in selected
                    isEnabled=!readOnly
                    setOnCheckedChangeListener { _,checked ->
                        if(checked)selected.add(value) else selected.remove(value)
                        scheduleDraftSave()
                    }
                })
            }
        }
    }

    private fun selector(parent: LinearLayout, title: String, opts: List<String>, value: String): Spinner {
        parent.addView(label(title,13f,true))
        val control=Spinner(this).apply {
            adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,
                listOf("Selecione") + opts)
            val n=(listOf("Selecione")+opts).indexOf(value)
            setSelection(n.coerceAtLeast(0))
            onItemSelectedListener=object: android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p:android.widget.AdapterView<*>?,v:View?,n:Int,id:Long) {
                    scheduleDraftSave()
                }
                override fun onNothingSelected(p:android.widget.AdapterView<*>?) {}
            }
        }
        parent.addView(control,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=sp(9) })
        return control
    }

    private fun selected(control: Spinner): String =
        control.selectedItem?.toString()?.takeUnless { it=="Selecione" }.orEmpty()

    private fun staffEditor(parent: LinearLayout, initial: List<BAServidor>, readOnly: Boolean = false): MutableList<Pair<EditText,EditText>> {
        val entries=mutableListOf<Pair<EditText,EditText>>()
        val container=vertical();parent.addView(container)
        fun add(name: String, matricula: String) {
            val section=vertical()
            val row=row()
            val n=field(section,"Nome do servidor",value=name)
            val m=field(section,"Matrícula do servidor",value=matricula)
            if(!readOnly) {
                m.addTextChangedListener(object:TextWatcher {
                    private var query:Runnable?=null
                    override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){
                        query?.let{m.removeCallbacks(it)}
                        val mat=s?.toString()?.trim().orEmpty()
                        if(mat.length<3)return
                        val job=Runnable{
                            val found=HubDirectory.cached(this@MainActivity,mat)
                            if(found!=null && m.text.toString().trim()==mat &&
                                (n.text.isBlank() || n.text.toString()==name)) {
                                n.setText(found.nome)
                            }
                        }
                        query=job
                        m.postDelayed(job,300L)
                    }
                    override fun afterTextChanged(s:Editable?){}
                })
            }
            if(!readOnly) {
                section.addView(button("Remover servidor",false) {
                    container.removeView(section)
                    entries.remove(n to m)
                    scheduleDraftSave()
                })
            } else {n.isEnabled=false;m.isEnabled=false}
            container.addView(section)
            entries.add(n to m)
            listOf(n,m).forEach { edit ->
                edit.addTextChangedListener(object:TextWatcher {
                    override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){scheduleDraftSave()}
                    override fun afterTextChanged(s:Editable?){}
                })
            }
        }
        initial.forEach { add(it.nome,it.matricula) }
        if(!readOnly) parent.addView(button("＋ Adicionar servidor",false){add("","");scheduleDraftSave()})
        return entries
    }

    private fun staffValues(inputs: List<Pair<EditText,EditText>>): List<BAServidor> =
        inputs.map { BAServidor(it.first.text.toString().trim(),it.second.text.toString().trim()) }
            .filter { it.nome.isNotBlank() || it.matricula.isNotBlank() }

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
    /**
     * Android 15 desenha atras da barra de gestos. A margem inferior segue
     * os WindowInsets reais, e a navegacao desaparece quando abre o teclado.
     * Chamado em todas as telas com barra inferior.
     */
    private fun safeBottomNavigation(root: View, navigation: View?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Sempre usar o inset real, sem somar duas vezes após redibujar a tela.
            val initialBottom=0
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars=insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                val keyboard=insets.getInsets(WindowInsets.Type.ime()).bottom
                val keyboardOpen=insets.isVisible(WindowInsets.Type.ime())
                navigation?.visibility=if(keyboardOpen) View.GONE else View.VISIBLE
                val bottom=initialBottom+maxOf(bars,if(keyboardOpen)keyboard else 0)
                if(view.paddingBottom!=bottom) view.setPadding(
                    view.paddingLeft,view.paddingTop,view.paddingRight,bottom)
                insets
            }
            root.requestApplyInsets()
        } else {
            val densityBottom=sp(18)
            root.setPadding(root.paddingLeft,root.paddingTop,root.paddingRight,
                root.paddingBottom+densityBottom)
        }
    }

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
        flushDraft()
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
        flushDraft()
        refreshVisibleScreen = null
        val shell = vertical().apply { setBackgroundColor(darkBg) }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = vertical()
        scroll.addView(content)
        shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(shell)
        safeBottomNavigation(shell,null)
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
        safeBottomNavigation(root,bar)
    }

    private fun goToMaps(o: Incident) {
        if (!has(Rights.MAP)) return toast("Sem permissão para acessar o mapa")
        val address = listOf(o.rua, o.bairro, "Sapucaia do Sul RS").filter { it.isNotBlank() }.joinToString(", ")
        val uri = Uri.parse("geo:0,0?q=${Uri.encode(address)}")
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        catch (_: Exception) { toast("Não há aplicativo de mapas disponível") }
    }

    /** Apenas anexos já recebidos pelo HUB podem ser limpos e restaurados. */
    private fun storageManagement() {
        val body=screen("Armazenamento do aparelho",
            "Cópias locais verificadas no SERVIDOR02",::dashboard)
        val card=section(body,"Liberação segura de espaço")
        card.addView(label("A ocorrência e seu histórico permanecem no Sentinela. "+
            "Somente fotos e PDFs de atendimentos concluídos e confirmados pelo HUB "+
            "podem ser removidos deste aparelho. Arquivos pessoais da galeria "+
            "não são apagados.",13f))
        val options=listOf("Mais antigos que 1 hora","Mais antigos que 24 horas",
            "Mais antigos que 15 dias","Mais antigos que 30 dias")
        val periods=listOf(3_600_000L,86_400_000L,
            15L*86_400_000L,30L*86_400_000L)
        val choice=Spinner(this).apply {
            adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,options)
            setSelection(2)
        }
        card.addView(label("Período de limpeza",13f,true))
        card.addView(choice)
        val info=label("Aguardando verificação. Nenhum documento será excluído "+
            "sem confirmação.",12f)
        card.addView(info)
        card.addView(button("Verificar arquivos que podem ser limpos"){
            info.text="Consultando arquivos arquivados no HUB..."
            val age=periods[choice.selectedItemPosition]
            StorageCleaner.clean(applicationContext,age,preview=true){result->
                if(result.error!=null) {
                    info.text="Não foi possível verificar: "+result.error
                    return@clean
                }
                val mb=String.format(Locale("pt","BR"),"%.2f",result.bytes/1048576.0)
                info.text="${result.arquivos} arquivo(s) elegíveis: ${mb} MB"
                if(result.arquivos==0)return@clean
                AlertDialog.Builder(this).setTitle("Confirmar limpeza segura?")
                    .setMessage("Liberar aproximadamente ${mb} MB de "+
                        "${result.arquivos} cópias locais?\n\n"+
                        "O HUB já confirmou os arquivos por checksum. "+
                        "A consulta e a recuperação continuarão disponíveis. "+
                        "Ocorrências e documentos do servidor NÃO serão apagados.")
                    .setNegativeButton("Cancelar",null)
                    .setPositiveButton("Liberar espaço"){_,_->
                        info.text="Limpando cópias confirmadas..."
                        StorageCleaner.clean(applicationContext,age,preview=false){done->
                            val freed=String.format(Locale("pt","BR"),
                                "%.2f",done.bytes/1048576.0)
                            info.text=if(done.error==null)
                                "Limpeza concluída: ${done.arquivos} arquivo(s), ${freed} MB."
                                else "Limpeza parcial: ${done.arquivos} arquivo(s). "+done.error
                        }
                    }.show()
            }
        })
        card.addView(label("Não limpe arquivos com envio pendente ou em conflito. "+
            "Para recuperar documentos de uma ocorrência, abra seus detalhes "+
            "e selecione 'Restaurar arquivos neste celular'.",12f))
    }

    private fun profileScreen() {
        val u=currentUser ?:return showLogin()
        val body=screen("Minha área", "${u.name} • ${u.matricula}",::dashboard)
        val info=section(body,"Meu acesso")
        info.addView(label("Perfil: ${u.role}\nMatrícula: ${u.matricula}",14f))
        val sig=store.userById(u.id)?.signaturePath.orEmpty()
        info.addView(label(if(sig.isNotBlank() && File(sig).isFile)
            "Assinatura institucional cadastrada" else "Assinatura institucional pendente",12f))
        info.addView(button("Cadastrar / atualizar minha assinatura",false){
            val target=File(File(filesDir,"assinaturas"),u.matricula+".png")
            collectSignature("Minha assinatura institucional",target){path->
                store.saveRegisteredSignature(u.id,path)
                HubSignature.markPending(this,u.matricula)
                HubSignature.sync(applicationContext,u.matricula){uploaded->
                    toast(if(uploaded)"Assinatura atualizada no HUB" else
                        "Assinatura salva neste celular; envio pendente")
                }
                profileScreen()
            }
        })
        info.addView(label("Permissões disponíveis: ${Rights.custom.filter { has(it) }.joinToString { Rights.labels[it] ?: it }}",12f))
        info.addView(button("Alterar minha senha", false) { changePasswordAtLogin(u) })
        info.addView(button("Gerenciar armazenamento e arquivos",false) {storageManagement()})
        info.addView(button("Sair da conta",false) { explicitLogout() })
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

    private fun showLogin() {
        flushDraft()
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
                            .putBoolean("hub_verified_" + identity.matricula,true)
                            .putBoolean("session_active",true).apply()
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
                        prefs.edit().putBoolean("session_active",true).apply()
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
                try {
                    val actor=currentUser ?: error("Usuário não autenticado")
                    store.acceptIncident(id,actor.id)
                    val extrasStore=BAExtrasStore(store)
                    val previous=extrasStore.get(id)
                    val name=actor.name+" ("+actor.matricula+")"
                    extrasStore.save(id,previous.copy(aceites=(previous.aceites+name).distinct()))
                    scheduleSync()
                    toast("Atendimento aceito")
                    openIncident(id)
                }
                catch(e:Exception){toast(e.message?:"Não foi possível aceitar")}
            }.show()
    }

    private fun newIncident() {
        if (!canOpen()) return toast("Somente operador ou administrador abre ocorrencias")
        val raw=prefs.getString("novo_rascunho","{}").orEmpty()
        val draft=try { JSONObject(raw) } catch (_:Exception) { JSONObject() }
        val body=screen("Nova ocorrência","Operador • Dados que integrarão automaticamente o BA",::dashboard)
        val c=section(body,"Dados do solicitante")
        val cat=selector(c,"Categoria",listOf("OCORRENCIAS","VISTORIAS","APOIOS OPERACIONAIS","INTERDIÇÕES"),draft.optString("categoria","OCORRENCIAS"))
        val solicitante=field(c,"Solicitante",value=draft.optString("solicitante"))
        val cpf=field(c,"CPF",value=draft.optString("cpf"))
        val telefone=field(c,"Telefone",value=draft.optString("telefone"))
        val rua=field(c,"Endereço (rua e número)",value=draft.optString("rua"))
        val bairro=field(c,"Bairro",value=draft.optString("bairro"))
        val classificacao=section(body,"Tipo do solicitante e atendimento")
        val tipoSolicitante=selector(classificacao,"Tipo do solicitante",
            listOf("Proprietário","Inquilino","Vizinho","Parente","Outros"),draft.optString("tipo_solicitante"))
        val tipoAtendimento=selector(classificacao,"Modalidade",
            listOf("Emergencial","Preventiva"),draft.optString("tipo_atendimento"))
        val tipos=section(body,"Tipos de ocorrência • Marque uma ou mais opções")
        val selectedTypes=mutableSetOf<String>()
        val savedTypes=draft.optJSONArray("tipos_ocorrencia")
        if(savedTypes!=null)for(i in 0 until savedTypes.length())selectedTypes.add(savedTypes.optString(i))
        typeChecklist(tipos,selectedTypes)
        val outro=field(tipos,"Outro tipo de ocorrência (quando não constar na lista)",
            value=draft.optString("outro"))
        val detalhes=section(body,"Informações iniciais e prioridade")
        val descricao=field(detalhes,"Histórico inicial / informações do solicitante",
            value=draft.optString("descricao"))
        val equipe=field(detalhes,"Equipe designada",value=draft.optString("equipe"))
        val prioridade=selector(detalhes,"Prioridade",listOf("Normal","Baixa","Alta","Urgente"),
            draft.optString("prioridade","Normal"))
        val servidores=section(body,"Servidores responsáveis")
        val savedStaff=draft.optJSONArray("servidores")
        val initial=if(savedStaff==null) emptyList() else (0 until savedStaff.length()).mapNotNull { i ->
            savedStaff.optJSONObject(i)?.let { BAServidor(it.optString("nome"),it.optString("matricula")) }
        }
        val staff=staffEditor(servidores,initial)

        fun capture(): JSONObject = JSONObject().apply {
            put("categoria",selected(cat));put("solicitante",solicitante.text.toString())
            put("cpf",cpf.text.toString());put("telefone",telefone.text.toString())
            put("rua",rua.text.toString());put("bairro",bairro.text.toString())
            put("tipo_solicitante",selected(tipoSolicitante))
            put("tipo_atendimento",selected(tipoAtendimento))
            put("tipos_ocorrencia",JSONArray().apply { selectedTypes.forEach { put(it) } })
            put("outro",outro.text.toString());put("descricao",descricao.text.toString())
            put("equipe",equipe.text.toString());put("prioridade",selected(prioridade))
            put("servidores",JSONArray().apply { staffValues(staff).forEach {
                put(JSONObject().put("nome",it.nome).put("matricula",it.matricula)) } })
        }
        watchDraft(listOf(solicitante,cpf,telefone,rua,bairro,outro,descricao,equipe)) {
            prefs.edit().putString("novo_rascunho",capture().toString()).commit()
        }
        servidores.addView(label("A lista de servidores poderá ser corrigida no BA.",12f))
        val actions=section(body,"Gravação do operador")
        actions.addView(button("Registrar ocorrência") {
            try {
                val type=selectedTypes.firstOrNull() ?: outro.text.toString().trim()
                if(type.isBlank()) return@button toast("Selecione ao menos um tipo de ocorrência")
                if(solicitante.text.isBlank()||rua.text.isBlank())
                    return@button toast("Informe solicitante e endereço")
                val item=store.createIncident(
                    selected(cat).ifBlank{"OCORRENCIAS"},solicitante.text.toString().trim(),
                    telefone.text.toString().trim(),rua.text.toString().trim(),bairro.text.toString().trim(),
                    type,descricao.text.toString(),equipe.text.toString(),
                    selected(prioridade).ifBlank{"Normal"},currentUser!!.id)
                BAExtrasStore(store).save(item.id,BAExtras(
                    solicitanteCpf=cpf.text.toString().trim(),
                    tipoSolicitante=selected(tipoSolicitante),
                    tipoAtendimento=selected(tipoAtendimento),
                    tiposOcorrencia=selectedTypes.toList(),
                    outroTipo=outro.text.toString().trim(),
                    servidores=staffValues(staff)
                ))
                activeDraftSaver=null
                prefs.edit().remove("novo_rascunho").apply()
                scheduleSync()
                toast("Ocorrência registrada. Pasta será criada no HUB após sincronização.")
                openIncident(item.id)
            } catch (e:Exception) { toast(e.message ?: "Falha ao registrar") }
        })
        actions.addView(label("Dados incompletos serão preservados como rascunho neste aparelho.",12f))
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
        val participants=(store.acceptanceNames(id)+BAExtrasStore(store).get(id).aceites).distinct()
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
        if(StorageCleaner.hasEvicted(this,id)){
            docs.addView(label("Alguns anexos foram limpos deste aparelho; "+
                "os documentos originais continuam no HUB.",12f))
            docs.addView(button("Restaurar arquivos neste celular",false){
                toast("Solicitando arquivos ao HUB...")
                StorageCleaner.restore(applicationContext,id){result->
                    toast(if(result.error!=null)
                        "Restauração parcial: "+result.error
                        else "${result.arquivos} arquivo(s) recuperados")
                    openIncident(id)
                }
            })
        }
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
        val o=store.incident(id) ?: return dashboard()
        val old=store.report(id)
        val oldExtra=BAExtrasStore(store).get(id)
        val readonly=o.status=="Atendida" || !store.canOperate(id,currentUser!!.id,Rights.BA)
        val body=screen("Boletim de Atendimento","Protocolo: "+o.number+" • Dados editáveis", {openIncident(id)})
        val dados=section(body,"Dados do solicitante — recebidos do operador")
        val solicitante=field(dados,"Solicitante",value=o.solicitante)
        val cpf=field(dados,"CPF do solicitante",value=oldExtra.solicitanteCpf)
        val telefone=field(dados,"Telefone do solicitante",value=o.telefone)
        val rua=field(dados,"Endereço (rua e número)",value=o.rua)
        val bairro=field(dados,"Bairro",value=o.bairro)
        val classificacao=section(body,"Tipo do solicitante e atendimento")
        val tipoSolicitante=selector(classificacao,"Tipo do solicitante",
            listOf("Proprietário","Inquilino","Vizinho","Parente","Outros"),oldExtra.tipoSolicitante)
        val modalidade=selector(classificacao,"Atendimento",listOf("Emergencial","Preventiva"),oldExtra.tipoAtendimento)
        val tipos=section(body,"Tipos de ocorrência — marque uma ou mais")
        val selectedTypes=oldExtra.tiposOcorrencia.toMutableSet()
        if (selectedTypes.isEmpty() && o.tipo.isNotBlank()) selectedTypes.add(o.tipo)
        typeChecklist(tipos,selectedTypes,readonly)
        val outroTipo=field(tipos,"Outros — informar tipo diferente",value=oldExtra.outroTipo)

        val responsavelBox=section(body,"Responsável no momento da vistoria")
        val responsavel=field(responsavelBox,"Nome do responsável",
            value=old.responsavel.ifBlank { o.solicitante })
        val doc=field(responsavelBox,"CPF / documento do responsável",value=old.responsavelDocumento)
        val respTelefone=field(responsavelBox,"Telefone do responsável",value=oldExtra.responsavelTelefone)
        val vinculo=field(responsavelBox,"Vínculo",value=oldExtra.responsavelVinculo)
        if(!readonly) responsavelBox.addView(button("Mesmo que o solicitante",false) {
            responsavel.setText(solicitante.text.toString())
            doc.setText(cpf.text.toString())
            respTelefone.setText(telefone.text.toString())
            vinculo.setText("Próprio solicitante")
        })

        val historicoBox=section(body,"Histórico da vistoria")
        historicoBox.addView(label("O histórico inicial do operador pode ser completado ou corrigido.",12f))
        val historico=field(historicoBox,"Histórico / constatações",
            value=old.historico.ifBlank { o.descricao })
        val providencias=field(section(body,"Providências adotadas"),
            "Providências adotadas",value=old.providencias)
        val servidores=section(body,"Servidores responsáveis — editáveis")
        val staff=staffEditor(servidores,oldExtra.servidores,readonly)
        val outros=field(servidores,"Observações sobre outros agentes",value=old.outrosAgentes)
        val gpsBlock=section(body,"Localização da ocorrência")
        val gps=field(gpsBlock,
            "Coordenadas / precisão / horário de captura",value=old.gps)
        if(!readonly)gpsBlock.addView(button("📍 Capturar GPS deste atendimento",false) {
            fillGps(gps)
        })

        val firstAgent=oldExtra.servidores.firstOrNull()
        val firstMat=firstAgent?.matricula?.trim().orEmpty().ifBlank {
            old.agenteMatricula.ifBlank{currentUser?.matricula.orEmpty()}
        }
        val firstName=firstAgent?.nome?.trim().orEmpty().ifBlank{
            old.agenteNome.ifBlank{currentUser?.name.orEmpty()}
        }
        val ownFirst=firstMat==currentUser?.matricula
        val signer=section(body,"Assinatura do primeiro agente")
        signer.addView(label("Somente o primeiro servidor listado assina o BA. "+
            "Matrícula e nome são recuperados do cadastro institucional.",12f))
        val mat=field(signer,"Matrícula do primeiro agente",value=firstMat)
        val agentName=field(signer,"Nome do primeiro agente",value=firstName)
        mat.isEnabled=false
        agentName.isEnabled=false
        val myRegisteredSignature=if(ownFirst)store.userById(currentUser!!.id)?.signaturePath.orEmpty()
            else ""
        var agentSignature=if(old.agenteMatricula==firstMat && old.agenteSignature.isNotBlank()
            && File(old.agenteSignature).isFile)old.agenteSignature
            else myRegisteredSignature.takeIf{it.isNotBlank()&&File(it).isFile}.orEmpty()
        val agentLabel=label(if(agentSignature.isNotBlank())
            "Assinatura cadastrada pronta para integrar o BA" else
            "Assinatura do primeiro agente pendente",12f)
        signer.addView(agentLabel)
        if(!readonly && ownFirst) signer.addView(button(
            if(agentSignature.isBlank())"Cadastrar assinatura do primeiro agente"
            else "Atualizar assinatura cadastrada",false) {
            val folder=File(filesDir,"assinaturas").apply{mkdirs()}
            val target=File(folder,currentUser!!.matricula+".png")
            collectSignature("Assinatura de "+currentUser!!.name,target){path->
                store.saveRegisteredSignature(currentUser!!.id,path)
                HubSignature.markPending(this,currentUser!!.matricula)
                HubSignature.sync(applicationContext,currentUser!!.matricula)
                agentSignature=path
                agentLabel.text="Assinatura do primeiro agente cadastrada"
                scheduleDraftSave()
            }
        })
        if(!readonly && !ownFirst)signer.addView(label(
            "Este usuário não é o primeiro agente. A assinatura deverá ser confirmada "+
                "pelo titular da matrícula "+firstMat+" em sua própria sessão.",12f))
        var responsibleSignature=old.responsavelSignature
        val resident=section(body,"Assinatura do responsável no local")
        val residentLabel=label(if(responsibleSignature.isNotBlank() && File(responsibleSignature).exists())
            "Assinatura registrada" else "Assinatura pendente",12f)
        resident.addView(residentLabel)
        if(!readonly)resident.addView(button("Coletar assinatura na tela",false) {
            collectSignature("Assinatura do responsável",
                File(FileTools.dir(this,id),"assinatura_responsavel.png")) {path ->
                responsibleSignature=path;residentLabel.text="Assinatura registrada"
                scheduleDraftSave()
            }
        })

        fun report(): Report {
            val first=staffValues(staff).firstOrNull()
            val finalMat=first?.matricula?.ifBlank{firstMat}?:firstMat
            val finalName=first?.nome?.ifBlank{firstName}?:firstName
            // Se mudou o primeiro agente, a assinatura anterior não é reutilizada.
            val signed=agentSignature.takeIf{
                finalMat==firstMat && (ownFirst || old.agenteMatricula==firstMat)
            }.orEmpty()
            return Report(id,historico.text.toString().trim(),
                providencias.text.toString().trim(),responsavel.text.toString().trim(),
                doc.text.toString().trim(),finalMat,finalName,
                signed,responsibleSignature,outros.text.toString().trim(),
                gps.text.toString().trim(),LocalStore.now())
        }

        fun extras(): BAExtras = oldExtra.copy(
            solicitanteCpf=cpf.text.toString().trim(),
            tipoSolicitante=selected(tipoSolicitante),
            tipoAtendimento=selected(modalidade),
            tiposOcorrencia=selectedTypes.toList(),
            outroTipo=outroTipo.text.toString().trim(),
            responsavelTelefone=respTelefone.text.toString().trim(),
            responsavelVinculo=vinculo.text.toString().trim(),
            servidores=staffValues(staff))

        fun saveAll(automatic: Boolean) {
            if(readonly) return
            val author=currentUser?.id ?: return
            store.updateIncidentDetails(id,solicitante.text.toString().trim(),
                telefone.text.toString().trim(),rua.text.toString().trim(),
                bairro.text.toString().trim(),
                selectedTypes.firstOrNull() ?: outroTipo.text.toString().trim().ifBlank{o.tipo},
                historico.text.toString(),author)
            val extra=extras()
            val extrasStore=BAExtrasStore(store)
            if (extrasStore.get(id)!=extra) extrasStore.save(id,extra)
            store.saveReport(report(),author,automatic)
            scheduleSync()
        }

        val photosBlock=section(body,"Registro fotográfico")
        photosBlock.addView(label("${store.photos(id).size} fotografia(s) armazenada(s) neste aparelho",13f))
        if(!readonly && has(Rights.PHOTOS)) {
            photosBlock.addView(button("Tirar fotografia") {saveAll(true);takePhoto(id)})
            photosBlock.addView(button("Selecionar da galeria",false) {saveAll(true);choosePhoto(id)})
        }
        store.photos(id).forEachIndexed { ix,_ ->
            photosBlock.addView(label("Foto ${ix+1} — salva localmente",12f))
        }

        if(readonly) {
            listOf(solicitante,cpf,telefone,rua,bairro,outroTipo,responsavel,
                doc,respTelefone,vinculo,historico,providencias,outros,gps,mat,agentName)
                .forEach{it.isEnabled=false}
            tipoSolicitante.isEnabled=false;modalidade.isEnabled=false
        } else {
            watchDraft(listOf(solicitante,cpf,telefone,rua,bairro,outroTipo,
                responsavel,doc,respTelefone,vinculo,historico,providencias,
                outros,gps,mat,agentName)) { saveAll(true) }
        }

        val actions=section(body,"Gravação e conclusão")
        if(!readonly) {
            actions.addView(label("Salvamento automático ativo • Rascunho local • Sincronização com o HUB",12f))
            actions.addView(button("Salvar rascunho agora",false) {
                try {saveAll(false);toast("Rascunho salvo no aparelho")}
                catch(e:Exception){toast("Falha ao salvar: "+e.message)}
            })
            if(has(Rights.FINISH)) actions.addView(button("Finalizar atendimento e gerar BA") {
                val r=report()
                if(r.historico.isBlank()||r.providencias.isBlank())
                    return@button toast("Preencha histórico e providências")
                if(r.agenteSignature.isBlank()||!File(r.agenteSignature).exists())
                    return@button toast("Colete assinatura do agente")
                if(r.responsavelSignature.isBlank()||!File(r.responsavelSignature).exists())
                    return@button toast("Colete assinatura do responsável")
                val photoList=store.photos(id)
                if(photoList.isEmpty()) return@button toast("Registre ao menos uma fotografia")
                try {
                    saveAll(false)
                    val updated=store.incident(id) ?: o
                    val pdf=ReportPdf.generate(this,updated,store.report(id),photoList)
                    store.conclude(id,pdf.absolutePath,currentUser!!.id)
                    val completed=store.incident(id) ?: error("Ocorrência não encontrada")
                    TimelinePdf.generate(this,completed,store.auditEvents(id))
                    FileTools.archive(this,completed,store.report(id),
                        store.photos(id),store.auditEvents(id))
                    activeDraftSaver=null
                    scheduleSync()
                    toast("BA gerado. Arquivamento no HUB aguardando confirmação.")
                    openIncident(id)
                } catch(e:Exception){toast("Falha ao gerar BA: "+(e.message ?: "verifique os dados"))}
            })
        } else actions.addView(label("Atendimento concluído. Documento disponível para consulta.",12f))
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
                scheduleSync()
                toast("Foto otimizada e salva. Envio automático solicitado.")
            } catch (e: Exception) { toast("Não foi possível importar imagem: ${e.message}") }
        }
        reportScreen(id)
    }

    private fun userManagement() {
        if(currentUser?.role!="ADMIN")return dashboard()
        val body=screen("Gestão central de usuários","Contas e perfis no SERVIDOR02",::dashboard)
        body.addView(label("Os cadastros são administrados no HUB e valem para todos os aparelhos. "+
            "Não são criados usuários locais independentes.",12f))
        val create=section(body,"Novo servidor")
        val matricula=field(create,"Matrícula")
        val fullName=field(create,"Nome completo")
        val role=selector(create,"Função no sistema",listOf("agente","operador","leitura","admin"),"agente")
        val initial=field(create,"Senha provisória (mínimo 8 caracteres, letra e número)",password=true)
        val confirmation=field(create,"Confirmar senha provisória",password=true)
        val listBox=section(body,"Servidores cadastrados")
        fun reload(){
            listBox.removeAllViews()
            listBox.addView(label("Consultando contas no HUB...",12f))
            HubAdmin.list(applicationContext){accounts,error->
                if(!listBox.isAttachedToWindow)return@list
                listBox.removeAllViews()
                if(accounts==null){
                    listBox.addView(label("Gestão indisponível: "+(error?:"sem conexão")+
                        ". Os cadastros não foram modificados.",13f))
                    return@list
                }
                if(accounts.isEmpty())listBox.addView(label("Nenhuma conta encontrada",13f))
                accounts.forEach { user ->
                    val panel=vertical().apply{
                        background=shape(Color.rgb(245,248,253),10)
                        setPadding(sp(10),sp(10),sp(10),sp(10))
                    }
                    panel.addView(label(user.nome+" • "+user.matricula,14f,true))
                    panel.addView(label(user.perfil.uppercase()+" • "+
                        (if(user.bloqueado)"BLOQUEADO" else "ATIVO"),11f))
                    panel.addView(button("Editar nome e perfil",false){
                        val editor=vertical().apply{setPadding(sp(12),sp(6),sp(12),0)}
                        val edName=field(editor,"Nome completo",value=user.nome)
                        val edRole=selector(editor,"Função",
                            listOf("agente","operador","leitura","admin"),user.perfil)
                        AlertDialog.Builder(this).setTitle("Editar matrícula "+user.matricula)
                            .setView(editor).setNegativeButton("Cancelar",null)
                            .setPositiveButton("Salvar"){_,_->
                                val payload=JSONObject().put("action","update")
                                    .put("full_name",edName.text.toString().trim())
                                    .put("role",selected(edRole))
                                HubAdmin.write(applicationContext,user.matricula,payload){failure->
                                    if(failure!=null)toast(failure)
                                    else{toast("Cadastro atualizado no HUB");HubDirectory.refresh(applicationContext);reload()}
                                }
                            }.show()
                    })
                    if(user.matricula!=currentUser?.matricula){
                        panel.addView(button(if(user.bloqueado)"Reativar acesso" else "Bloquear acesso",false){
                            AlertDialog.Builder(this).setTitle("Confirmar alteração")
                                .setMessage((if(user.bloqueado)"Reativar " else "Bloquear ")+user.nome+"?")
                                .setNegativeButton("Cancelar",null)
                                .setPositiveButton("Confirmar"){_,_->
                                    val payload=JSONObject().put("action",
                                        if(user.bloqueado)"enable" else "disable")
                                    HubAdmin.write(applicationContext,user.matricula,payload){failure->
                                        if(failure!=null)toast(failure)
                                        else{toast("Acesso atualizado no HUB");reload()}
                                    }
                                }.show()
                        })
                        panel.addView(button("Redefinir senha provisória",false){
                            val editor=vertical().apply{setPadding(sp(12),sp(8),sp(12),0)}
                            val edPass=field(editor,"Nova senha",password=true)
                            val repeat=field(editor,"Confirme a nova senha",password=true)
                            AlertDialog.Builder(this).setTitle("Redefinir senha de "+user.nome)
                                .setView(editor).setNegativeButton("Cancelar",null)
                                .setPositiveButton("Alterar"){_,_->
                                    if(edPass.text.toString()!=repeat.text.toString()){
                                        toast("As senhas são diferentes")
                                    }else{
                                        val payload=JSONObject().put("action","reset_password")
                                            .put("password",edPass.text.toString())
                                        HubAdmin.write(applicationContext,user.matricula,payload){failure->
                                            if(failure!=null)toast(failure)
                                            else{toast("Senha atualizada no HUB");reload()}
                                        }
                                    }
                                }.show()
                        })
                    }
                    listBox.addView(panel,LinearLayout.LayoutParams(-1,-2).apply{
                        bottomMargin=sp(9)
                    })
                }
            }
        }
        create.addView(button("Cadastrar no HUB"){
            val password=initial.text.toString()
            if(matricula.text.isBlank()||fullName.text.isBlank())return@button toast(
                "Preencha matrícula e nome")
            if(password!=confirmation.text.toString())return@button toast(
                "As senhas não coincidem")
            val payload=JSONObject().put("username",matricula.text.toString().trim())
                .put("full_name",fullName.text.toString().trim())
                .put("role",selected(role)).put("password",password)
            HubAdmin.write(applicationContext,null,payload){failure->
                if(failure!=null)toast(failure)
                else{
                    toast("Servidor cadastrado no HUB")
                    matricula.setText("");fullName.setText("");initial.setText("")
                    confirmation.setText("")
                    HubDirectory.refresh(applicationContext)
                    reload()
                }
            }
        })
        reload()
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
