from pathlib import Path
import sys
root=Path(sys.argv[1]) if len(sys.argv)>1 else Path('/mnt/data/TEMPO_Android')
def patch(rel,old,new):
 p=root/rel
 s=p.read_text(encoding='utf-8')
 if old not in s:
  raise ValueError(f'Padrão não encontrado em {rel}: {old[:110]}')
 p.write_text(s.replace(old,new),encoding='utf-8')
app='app/src/main/assets/app.js'
html='app/src/main/assets/index.html'
java='app/src/main/java/br/com/iantzen/tempo/MainActivity.java'
bridge='app/src/main/java/br/com/iantzen/tempo/MonitorBridge.java'
patch('app/build.gradle',"versionCode 2\n        versionName '0.2.0'","versionCode 3\n        versionName '0.2.1'")
patch('app/src/main/AndroidManifest.xml','android:allowBackup="false"','android:allowBackup="false"\n        android:enableOnBackInvokedCallback="false"')
patch(java,'''    @Override public void onBackPressed() {
        if (webView!=null) webView.evaluateJavascript("window.TEMPOBack && window.TEMPOBack()",null);
        else super.onBackPressed();
    }
''','''    // Voltar: uma tela interna retorna à inicial; na inicial encerra a tarefa Android.
    @Override public void onBackPressed() {
        if (webView == null) { finishAndRemoveTask(); return; }
        webView.evaluateJavascript("window.TEMPOHandleBack ? window.TEMPOHandleBack() : 'exit'", result -> {
            if (!"\\\"handled\\\"".equals(result)) finishAndRemoveTask();
        });
    }
    @Override protected void onResume() {
        super.onResume();
        if(webView != null) webView.evaluateJavascript("window.TEMPOOnResume && window.TEMPOOnResume()", null);
    }
''')
patch(bridge,'    @JavascriptInterface public void fetchData(String source) {','''    @JavascriptInterface public void exitApp() {
        activity.runOnUiThread(activity::finishAndRemoveTask);
    }
    @JavascriptInterface public void fetchData(String source) {''')
patch(html,'''      <button class="head-action" data-go="alertas" aria-label="Abrir alertas"><svg><use href="#i-alert"/></svg></button>''','''      <button class="head-action" data-go="alertas" aria-label="Abrir alertas"><svg><use href="#i-alert"/></svg></button>
      <button class="head-action" id="header-exit" aria-label="Sair do aplicativo" title="Sair do TEMPO"><svg><use href="#i-close"/></svg></button>''')
patch(html,'''      <div class="white-card live-section" id="live-status-panel">''','''      <button type="button" id="monitor-refresh" class="source-refresh">Atualizar medições agora <svg><use href="#i-refresh"/></svg></button>
      <div class="white-card live-section" id="live-status-panel">''')
patch(html,'''      <a class="service-card featured" href="https://www.sapucaiadosul.rs.gov.br/wp-content/uploads/2026/08/Plano-de-Contingencia-Sapucaia-2026.pdf">''','''      <a class="service-card featured" href="https://www.sapucaiadosul.rs.gov.br/wp-content/uploads/2026/08/Plano-de-Contingencia-Sapucaia-2026.pdf">''')
patch(html,'''      <a class="service-card" href="https://sapucaia-aid-doc.lovable.app/">''','''      <a class="service-card" href="https://www.sapucaiadosul.rs.gov.br/defesa-civil-de-sapucaia-do-sul/"><span class="service-icon blue"><svg><use href="#i-external"/></svg></span><span><b>PLACON na página da Prefeitura</b><small>Alternativa ao PDF: acesse a página oficial e escolha o documento.</small></span><svg class="right-chevron"><use href="#i-arrow"/></svg></a>
      <a class="service-card" href="https://sapucaia-aid-doc.lovable.app/">''')
patch(html,'''      <div class="about"><img src="img/logo_tempo.webp"''','''      <button type="button" id="exit-app" class="service-card exit-service"><span class="service-icon orange-solid"><svg><use href="#i-close"/></svg></span><span><b>Sair do aplicativo</b><small>Encerra o TEMPO e volta à tela do celular</small></span><svg class="right-chevron"><use href="#i-arrow"/></svg></button>
      <div class="about"><img src="img/logo_tempo.webp"''')
patch(html,'Versão piloto 0.2.0 ·','Versão piloto 0.2.1 ·')
patch(html,'''      <a class="service-card" href="https://temposapucaia.lovable.app/"><span class="service-icon blue"><svg><use href="#i-info"/></svg></span><span><b>TEMPO — Painel web</b><small>Consulte as informações do sistema atual</small></span><svg class="right-chevron"><use href="#i-external"/></svg></a>''','''      <a class="service-card" href="https://redehidrometeorologica.defesacivil.rs.gov.br/"><span class="service-icon blue"><svg><use href="#i-info"/></svg></span><span><b>Monitoramento da Defesa Civil RS</b><small>Consultar o painel estadual oficial</small></span><svg class="right-chevron"><use href="#i-external"/></svg></a>''')
patch(app,'const CONFIG = {weatherTTL: 15*60*1000,','const CONFIG = {weatherTTL: 10*60*1000,')
patch(app,'url:\'https://temposapucaia.lovable.app/\',cta:\'Abrir monitoramento do rio\'','url:\'https://www.snirh.gov.br/hidrotelemetria/\',cta:\'Abrir Hidrotelemetria (ANA)\'')
patch(app,'url:\'https://temposapucaia.lovable.app/\',cta:\'Consultar pluviômetros no TEMPO web\'','url:\'https://www2.cemaden.gov.br/mapainterativo/\',cta:\'Abrir mapa oficial do CEMADEN\'')
patch(app,'url:\'https://temposapucaia.lovable.app/\',cta:\'Ver sensores no painel existente\'','url:\'https://redehidrometeorologica.defesacivil.rs.gov.br/\',cta:\'Abrir estações da Defesa Civil RS\'')
patch(app,'url:\'https://temposapucaia.lovable.app/\',cta:\'Abrir painel de sensores\'','url:\'https://redehidrometeorologica.defesacivil.rs.gov.br/\',cta:\'Abrir rede estadual de estações\'')
patch(app,"body:'A medição do nível do Rio dos Sinos ainda não está integrada a esta versão. Abra o painel TEMPO existente para consultar as informações e a origem dos dados. A ausência de medição não indica normalidade.'","body:'A leitura da ANA é feita diretamente pela internet, na estação de São Leopoldo. Caso a fonte não responda ou forneça medição antiga, o aplicativo indicará a indisponibilidade. A ausência de leitura não indica normalidade.'")
patch(app,"body:'O valor de chuva exibido na página inicial é uma estimativa do modelo Open-Meteo, não uma leitura de pluviômetro. Para dados medidos por estações, utilize o painel TEMPO atual e confira a fonte e o horário.'","body:'O valor de chuva estimada na previsão Open-Meteo não substitui a chuva medida no pluviômetro G2-Colonial do CEMADEN. O aplicativo consulta diretamente a estação e mostra o horário da leitura.'")
patch(app,"body:'A integração automática com as estações municipais está prevista para uma próxima etapa. Até lá, consulte o sistema existente e verifique se o sensor está operacional antes de usar seus dados em decisões.'","body:'A consulta da rede da Defesa Civil RS é realizada diretamente pela internet. Confira a estação, o horário e se a medição está disponível antes de tomar decisões.'")
patch(app,"const SOURCE_TTL={cemaden:5*60*1000,estado:5*60*1000,ana:2*60*1000,inmet:10*60*1000};","const SOURCE_TTL={cemaden:5*60*1000,estado:5*60*1000,ana:2*60*1000,inmet:5*60*1000};")
start=app
p=root/start
s=p.read_text(encoding='utf-8')
a=s.index('function msFromReading(str){')
b=s.index('\nfunction freshness(kind,obj){',a)
s=s[:a]+'''// Horários de Brasília sem fuso explícito são interpretados como UTC-03;
// leituras UTC e ISO com fuso são preservadas, sem deslocamento duplicado.
function msFromReading(raw){
 const s=String(raw||'').trim();
 let m=s.match(/(\\d{2})\\/(\\d{2})\\/(\\d{4})\\s+(\\d{2}):(\\d{2})(?::(\\d{2}))?/);
 if(m) return Date.parse(`${m[3]}-${m[2]}-${m[1]}T${m[4]}:${m[5]}:${m[6]||'00'}${/\\bUTC\\b/i.test(s)?'Z':'-03:00'}`);
 m=s.match(/(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2})(?::(\\d{2}))?(?:\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?/i);
 if(!m) return NaN;
 let tz=m[7]||(/\\bUTC\\b/i.test(s)?'Z':'-03:00');
 if(/^[+-]\\d{4}$/.test(tz))tz=tz.slice(0,3)+':'+tz.slice(3);
 return Date.parse(`${m[1]}-${m[2]}-${m[3]}T${m[4]}:${m[5]}:${m[6]||'00'}${tz}`);
}''' +s[b:]
p.write_text(s,encoding='utf-8')
patch(app,'const sourcePending={};','const sourcePending={};\nconst sourceLastAttempt={};')
patch(app,''' if(sourcePending[kind])return;
 if(!force&&sources[kind]?.ok&&!sources[kind].cached&&Date.now()-sources[kind].fetchedAt<SOURCE_TTL[kind])return;
 sourcePending[kind]=true;''',''' if(sourcePending[kind] || !navigator.onLine)return;
 if(!force && sourceLastAttempt[kind] && Date.now()-sourceLastAttempt[kind]<SOURCE_TTL[kind])return;
 if(!force&&sources[kind]?.ok&&!sources[kind].cached&&Date.now()-sources[kind].fetchedAt<SOURCE_TTL[kind])return;
 sourceLastAttempt[kind]=Date.now();
 sourcePending[kind]=true;''')
patch(app,"if(rm)rm.textContent=an?.ok?`ANA · São Leopoldo · ${freshness('ana',an).label}`:'ANA · São Leopoldo · indisponível';","if(rm)rm.textContent=an?.ok?`ANA · São Leopoldo · ${fmtReading(an.readingAt)} · ${freshness('ana',an).label}`:'ANA · São Leopoldo · indisponível';")
patch(app,''' setInterval(()=>{if(!document.hidden&&navigator.onLine)for(const k of Object.keys(SOURCE_LABELS))collectSource(k);},60000);
 document.addEventListener('visibilitychange',()=>{if(!document.hidden&&navigator.onLine)for(const k of Object.keys(SOURCE_LABELS))collectSource(k);});
 window.addEventListener('online',()=>{for(const k of Object.keys(SOURCE_LABELS))collectSource(k,true);});''',''' // Atualiza assim que a fonte pode ter um dado novo; não inventa medições a cada segundo.
 setInterval(()=>{if(!document.hidden&&navigator.onLine)for(const k of Object.keys(SOURCE_LABELS))collectSource(k);},30000);
 document.addEventListener('visibilitychange',()=>{if(!document.hidden)refreshOnResume();});
 window.addEventListener('online',refreshOnResume);''')
patch(app,'\nfunction init(){','''
let lastResumeRefresh=0;
function refreshOnResume(){
 if(demo || !navigator.onLine || document.hidden || Date.now()-lastResumeRefresh<12000)return;
 lastResumeRefresh=Date.now();
 updateWeather(false);
 for(const k of Object.keys(SOURCE_LABELS))collectSource(k);
 updateSourceUI();
}
window.TEMPOOnResume=refreshOnResume;
window.TEMPOHandleBack=()=>{
 if(page==='detail'){goto('monitorar');return 'handled';}
 if(page!=='inicio'){goto('inicio');return 'handled';}
 return 'exit';
};
window.TEMPOExit=()=>{
 if(window.TEMPO_NATIVE && typeof window.TEMPO_NATIVE.exitApp==='function')window.TEMPO_NATIVE.exitApp();
 else window.close();
};
function init(){''')
patch(app,''' $('#refresh-btn').addEventListener('click',()=>{if(demo)displayDemo();else {updateWeather(true);for(const k of Object.keys(SOURCE_LABELS))collectSource(k,true);}});''',''' function refreshAll(){if(demo){displayDemo();return;}updateWeather(true);for(const k of Object.keys(SOURCE_LABELS))collectSource(k,true);updateSourceUI();}
 $('#refresh-btn').addEventListener('click',refreshAll);
 $('#monitor-refresh').addEventListener('click',refreshAll);
 $('#header-exit').addEventListener('click',window.TEMPOExit);
 $('#exit-app').addEventListener('click',window.TEMPOExit);''')
patch(app,''' window.TEMPOBack=()=>{if(page==='detail')goto('monitorar');else goto('inicio');};
 document.addEventListener('keydown',evt=>{if(evt.key==='Escape'||evt.key==='Backspace'){if(page!=='inicio'){evt.preventDefault();window.TEMPOBack();}}});''',''' document.addEventListener('keydown',evt=>{if(evt.key==='Escape'||evt.key==='Backspace'){
  if(evt.target && ['INPUT','TEXTAREA'].includes(evt.target.tagName))return;
  evt.preventDefault();if(window.TEMPOHandleBack()==='exit')window.TEMPOExit();
 }});''')
patch(app,''' window.addEventListener('online',()=>{if(!demo)updateWeather(true)});''',''' window.addEventListener('online',refreshOnResume);
 // Previsão Open-Meteo tem atualização própria e não é sensor em tempo real.
 setInterval(()=>{if(!document.hidden&&!demo&&navigator.onLine)updateWeather(false);},60000);''')
patch(root and 'app/src/main/assets/style.css','\n/* TEMPO 0.2.1: comando sair e atualização das estações */' if False else '/* NO_MATCH */','') if False else None
with (root/'app/src/main/assets/style.css').open('a',encoding='utf-8') as f:
 f.write('''
/* TEMPO 0.2.1: controles de saída e atualização das fontes */
.head-action{flex-shrink:0}
.exit-action svg{width:20px;height:20px}
.source-refresh{display:flex;align-items:center;justify-content:center;gap:8px;width:100%;padding:13px 16px;border:1px solid #b3c3e4;border-radius:14px;background:#f0f5ff;color:#0c2a69;font-weight:700;font-size:14px;margin:10px 0 14px}
.source-refresh svg{width:19px;height:19px}
.exit-service{width:100%;text-align:left;margin-top:15px;cursor:pointer}
@media(max-width:360px){.brand-row{gap:6px}.brand-type strong{font-size:20px}.head-action{min-width:32px}}
''')
print('PATCH_APPLIED_021',root)