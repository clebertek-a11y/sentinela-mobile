from pathlib import Path
import re
p=Path('tempo'); java=p/'app/src/main/java/br/com/iantzen/tempo/MonitorBridge.java'; text=java.read_text()
text=text.replace('private static final String UA = "TEMPO-Sapucaia-Android/0.2";', 'private static final String UA = "TEMPO-Sapucaia-Android/0.3";')
text=text.replace('if (!source.equals("cemaden") && !source.equals("estado") && !source.equals("ana") && !source.equals("inmet")) return;','if (!source.equals("cemaden") && !source.equals("estado") && !source.equals("ana") && !source.equals("inmet") && !source.matches("cemaden_station:[0-9]{1,7}")) return;')
text=text.replace('if (source.equals("cemaden")) result=cemaden();','if (source.equals("cemaden")) result=cemaden();\n                else if (source.startsWith("cemaden_station:")) result=cemadenHourly(Integer.parseInt(source.substring(16)));')
a=text.index('    private JSONObject cemaden()throws Exception {');b=text.index('    private static String dates(Date d)',a)
replacement='''    /** Lista nacional do CEMADEN para 24h, filtrada por RS, tipo pluviométrico e rede oficial.
     * O horário updatedAt é da publicação do ARQUIVO, não prova leitura individual recente. */
    private JSONObject cemaden()throws Exception {
        String body=request("https://resources.cemaden.gov.br/dados/311_24.json","GET",null);
        JSONArray doc=new JSONArray(body);
        if (doc.length()==0) throw new Exception("Arquivo CEMADEN vazio");
        JSONObject root=doc.getJSONObject(0);
        JSONArray all=root.optJSONArray("estacao");
        if(all==null)throw new Exception("Sem estações no arquivo CEMADEN");
        JSONArray stations=new JSONArray();
        for(int i=0;i<all.length();i++){
            JSONObject s=all.optJSONObject(i);if(s==null)continue;
            if(!"RS".equalsIgnoreCase(s.optString("uf")))continue;
            if(!"Pluviométrica".equalsIgnoreCase(s.optString("tipoestacao")))continue;
            if(!"CEMADEN".equalsIgnoreCase(s.optString("sigla")))continue;
            JSONObject e=new JSONObject();
            e.put("id",s.optInt("idestacao"));
            e.put("code",s.optString("codestacao"));
            e.put("name",s.optString("nomeestacao").replace("¡","í"));
            e.put("city",s.optString("cidade"));
            e.put("ibge",s.optInt("codibge"));
            e.put("status",s.optInt("status",-1));
            putNumber(e,"h24",s.opt("acumulado"));
            putNumber(e,"latitude",s.opt("latitude"));
            putNumber(e,"longitude",s.opt("longitude"));
            stations.put(e);
        }
        JSONObject out=result();out.put("stations",stations);
        out.put("fileUpdatedAt",root.optString("atualizado"));
        out.put("source","CEMADEN — relatório de 24h (rede pluviométrica RS)");
        return out;
    }
    /** Dados horários individualizados sob demanda: lacunas são preservadas e valores não são inventados. */
    private JSONObject cemadenHourly(int stationId)throws Exception {
        if (stationId<1 || stationId>9999999) throw new Exception("ID inválido");
        JSONObject j=new JSONObject(request("https://mapservices.cemaden.gov.br/MapaInterativoWS/resources/horario/"+stationId+"/720","GET",null));
        JSONArray hours=j.optJSONArray("horarios"),dates=j.optJSONArray("datas"), rows=j.optJSONArray("acumulados");
        if(hours==null||dates==null||rows==null)throw new Exception("Resposta sem horários");
        JSONObject st=j.optJSONObject("estacao");
        JSONObject out=result();out.put("stationId",stationId);
        out.put("station",st==null?"Estação "+stationId:st.optString("nome","Estação "+stationId).replace("¡","í"));
        out.put("code",st==null?"":st.optString("codEstacao"));
        ArrayList<Long> times=new ArrayList<>();ArrayList<Double> values=new ArrayList<>();
        SimpleDateFormat df=new SimpleDateFormat("dd/MM/yyyy HH'h'", Locale.ROOT);
        df.setLenient(false);df.setTimeZone(TimeZone.getTimeZone("UTC"));
        long now=System.currentTimeMillis();
        for(int d=0;d<rows.length()&&d<dates.length();d++){
            JSONArray row=rows.optJSONArray(d);if(row==null)continue;
            for(int h=0;h<hours.length();h++){
                try{
                    Date date=df.parse(dates.optString(d)+" "+hours.optString(h));if(date==null)continue;
                    long stamp=date.getTime();if(stamp>now+3600_000)continue;
                    Object raw=h<row.length()?row.opt(h):null;double v=number(raw);
                    times.add(stamp);values.add(Double.isFinite(v)&&v>=0?v:Double.NaN);
                }catch(Exception ignored){}
            }
        }
        if(times.isEmpty())throw new Exception("Sem dados horários");
        // A janela termina na última hora com leitura, nunca em uma célula nula ou futura.
        int last=-1;for(int i=values.size()-1;i>=0;i--)if(Double.isFinite(values.get(i))){last=i;break;}
        if(last<0)throw new Exception("Estação sem leitura horária válida");
        long measurementAt=times.get(last);
        out.put("readingTs",measurementAt);
        SimpleDateFormat iso=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.ROOT);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));out.put("readingAt",iso.format(new Date(measurementAt)));
        putNumber(out,"h1",values.get(last));
        putNumber(out,"h6",rainWindow(values,last,6));
        putNumber(out,"h12",rainWindow(values,last,12));
        putNumber(out,"h24",rainWindow(values,last,24));
        JSONArray series=new JSONArray();
        for(int i=Math.max(0,last-23);i<=last;i++){
            JSONObject v=new JSONObject();v.put("time",iso.format(new Date(times.get(i))));
            putNumber(v,"mm",values.get(i));series.put(v);
        }
        out.put("series",series);out.put("source","CEMADEN — histórico horário da estação");
        return out;
    }
    private static double rainWindow(ArrayList<Double> list,int last,int count){
        if(last+1<count)return Double.NaN;
        double sum=0;
        for(int i=last-count+1;i<=last;i++){
            double v=list.get(i);if(!Double.isFinite(v))return Double.NaN;sum+=v;
        }
        return sum;
    }
'''
text=text[:a]+replacement+text[b:]
java.write_text(text)

html=p/'app/src/main/assets/index.html';s=html.read_text();
loc='<div class="shell" id="app">'
s=s.replace(loc,'''<div class="boot-splash" id="boot-splash" role="status" aria-live="polite">
  <div class="boot-shine"></div>
  <div class="boot-main">
    <img src="img/logo_tempo.webp" alt="Logotipo original do TEMPO" class="boot-logo" />
    <h1>TEMPO</h1>
    <p>Tecnologia Estratégica Móvel<br>para Previsão Operacional</p>
    <div class="boot-loader"><span></span></div>
    <div class="boot-status" id="boot-status">Conectando às fontes meteorológicas...</div>
    <button type="button" id="boot-skip" class="boot-skip">Acessar painel</button>
  </div>
  <div class="boot-signature"><small>Uma solução tecnológica de</small><div class="iantzen-word"><span>IA</span>ntzen</div><b>TECNOLOGIA</b><small>Soluções Inteligentes</small></div>
</div>
'''+loc)
s=s.replace('Versão piloto 0.2.1 · Estações conectadas via internet','Versão piloto 0.3.0 · Fontes conectadas via internet')
s=s.replace('Consulte o painel meteorológico existente','Rede estadual conectada à internet')
html.write_text(s)

css=p/'app/src/main/assets/style.css';s=css.read_text();s+='''
/* Abertura TEMPO · usa ícone original, sem criar brasões adicionais. */
.boot-splash{position:fixed;inset:0;z-index:500;background:linear-gradient(162deg,#071b44 4%,#0c337e 56%,#051d49 100%);display:flex;flex-direction:column;align-items:center;justify-content:space-between;overflow:hidden;text-align:center;color:#fff;padding:clamp(34px,8vh,90px) 22px max(36px,env(safe-area-inset-bottom));transition:opacity .36s ease,visibility .36s ease}
.boot-splash.hidden{opacity:0;visibility:hidden;pointer-events:none}
.boot-shine{position:absolute;pointer-events:none;width:120vw;height:33vh;top:-14vh;left:-10vw;transform:rotate(-14deg);background:linear-gradient(110deg,transparent,#f78b1b2b,transparent)}
.boot-main{position:relative;display:flex;flex-direction:column;align-items:center;margin:auto 0 0;gap:10px}
.boot-logo{width:min(43vw,174px);height:min(43vw,174px);object-fit:contain;border-radius:28px;box-shadow:0 22px 50px #03153275}
.boot-main h1{font-size:clamp(40px,10vw,54px);line-height:1;font-weight:900;letter-spacing:2px;margin:6px 0 0}
.boot-main p{font-size:12px;line-height:1.55;color:#d7e8ff;margin:0}
.boot-loader{width:148px;height:4px;border-radius:30px;overflow:hidden;background:#ffffff38;margin:24px 0 2px}
.boot-loader span{height:100%;width:36%;display:block;background:#ff8c1a;border-radius:30px;animation:boot-progress 1.45s ease-in-out infinite}
@keyframes boot-progress{0%{transform:translateX(-110%)}100%{transform:translateX(410%)}}
.boot-status{font-size:11px;line-height:1.5;max-width:300px;color:#d7e8ff;min-height:18px}
.boot-skip{margin-top:8px;color:#fff;border:1px solid #ffffff83;border-radius:20px;background:#ffffff18;padding:8px 16px;font-size:12px;font-weight:700}
.boot-signature{position:relative;margin:35px 0 0;display:flex;align-items:center;flex-direction:column;gap:3px}
.boot-signature>small{font-size:10px;color:#bdcdea}.iantzen-word{font-size:26px;font-weight:800;letter-spacing:.3px;color:#f5871d}.iantzen-word span{color:#d8e0eb}.boot-signature>b{font-size:10px;letter-spacing:4px;color:#fff}
@media(prefers-reduced-motion:reduce){.boot-loader span{animation:none;width:100%}}
/* Comparativo CEMADEN */
.rain-toolbar{display:flex;gap:7px;overflow-x:auto;padding:8px 0 12px}.rain-pill{border-radius:999px;border:1px solid #c3d5ec;background:#eff5ff;color:#20416e;padding:9px 13px;font-size:12px;font-weight:700;white-space:nowrap}.rain-pill.active{background:#0e397f;color:#fff;border-color:#0e397f}
.rain-search{width:100%;padding:12px;border:1px solid #c5d4e9;border-radius:12px;font-size:14px;background:#fff;color:#162b4a;margin-bottom:10px}
.rain-summary{font-size:12px;color:var(--muted);margin:8px 0 12px}.rain-list{max-height:48vh;overflow-y:auto;border-radius:12px;border:1px solid var(--border,#dae3ef)}
.rain-station{display:flex;width:100%;align-items:center;gap:12px;min-height:65px;padding:10px 12px;border:0;border-bottom:1px solid #e4eaf2;text-align:left;background:var(--paper,#fff);color:var(--text,#203a5f)}
.rain-station:last-child{border:0}.rain-station strong{font-size:13px;display:block}.rain-station small{display:block;color:var(--muted);font-size:11px;margin-top:4px}.rain-station .rain-mm{margin-left:auto;font-size:17px;font-weight:800;color:#0a4b95;white-space:nowrap}
.rain-station .rain-mm.missing{color:#9b5d14}.rain-bar{width:70px;height:5px;border-radius:10px;background:#dae6fa;margin-top:5px;overflow:hidden}.rain-bar span{display:block;height:100%;background:linear-gradient(90deg,#2192dc,#f08a28)}
.rain-hour-bars{display:flex;gap:3px;align-items:end;min-height:74px;padding:10px 0}.rain-hour-col{flex:1;min-width:0;display:flex;flex-direction:column;justify-content:end;gap:2px}.rain-hour-col span{height:var(--barheight);background:#1c80ca;border-radius:2px 2px 0 0;min-height:2px}.rain-hour-col.missing span{background:repeating-linear-gradient(120deg,#a5a5a5,#a5a5a5 2px,#e3e3e3 2px,#e3e3e3 4px)}
[data-theme=dark] .rain-station{background:#122b55;color:#eef5ff;border-color:#355179}[data-theme=dark] .rain-station .rain-mm{color:#9fc8ff}[data-theme=dark] .rain-pill{background:#1b3865;color:#eef5ff;border-color:#46618c}[data-theme=dark] .rain-pill.active{background:#ff8a18;color:#142a48}[data-theme=dark] .rain-search{background:#1b3865;color:#fff;border-color:#5674a4}
''';css.write_text(s)

js=p/'app/src/main/assets/app.js';s=js.read_text();
s=s.replace('const SOURCE_LABELS={cemaden:\'CEMADEN · G2-Colonial\'','const SOURCE_LABELS={cemaden:\'CEMADEN · pluviômetros do RS\'')
s=s.replace("const sources={};", "const sources={};\nlet rainScope='sapucaia', rainSearch='', selectedRainId=null;const rainStationDetails={};let rainStationPending=false;")
s=s.replace("if(kind==='cemaden'){\n   const age=Date.now()-msFromReading(obj.readingAt);\n   if(!Number.isFinite(age)||age<0||age>45*60*1000)return {level:'saved',label:'Verificar horário'};\n }", "if(kind==='cemaden'){\n   const age=Date.now()-msFromReading(obj.fileUpdatedAt);\n   if(!Number.isFinite(age)||age<-60000||age>30*60*1000)return {level:'saved',label:'Arquivo antigo'};\n }")
s=s.replace("function saveSource(kind,obj){try{if(obj.ok)localStorage.setItem('tempo_sensor_'+kind,JSON.stringify({savedAt:Date.now(),data:obj}));}catch{}}", "function saveSource(kind,obj){try{if(obj.ok)localStorage.setItem('tempo_sensor_'+kind+(kind==='cemaden'?'_v03':''),JSON.stringify({savedAt:Date.now(),data:obj}));}catch{}}")
s=s.replace("function loadSource(kind){try{let x=JSON.parse(localStorage.getItem('tempo_sensor_'+kind)||'null');if(x&&x.data)return {...x.data,cached:true};}catch{}return null;}", "function loadSource(kind){try{let x=JSON.parse(localStorage.getItem('tempo_sensor_'+kind+(kind==='cemaden'?'_v03':'')||'null')||'null');if(x&&x.data)return {...x.data,cached:true};}catch{}return null;}")
# Fix cache syntax in next step if necessary
s=s.replace("localStorage.getItem('tempo_sensor_'+kind+(kind==='cemaden'?'_v03':'')||'null')||'null'", "localStorage.getItem('tempo_sensor_'+kind+(kind==='cemaden'?'_v03':''))||'null'")
s=s.replace("window.TEMPO_OnNativeData=(kind,json)=>{\n if(!(kind in SOURCE_LABELS))return;", "window.TEMPO_OnNativeData=(kind,json)=>{\n if(kind.startsWith('cemaden_station:')){\n   rainStationPending=false;\n   let v;try{v=JSON.parse(json);}catch{v={ok:false};}\n   if(v.ok)rainStationDetails[kind.slice(16)]=v;\n   if(page==='detail'&&window.detailTopic==='chuvas')renderDetail('chuvas');\n   return;\n }\n if(!(kind in SOURCE_LABELS))return;")
s=s.replace(" updateSourceUI();\n};\nfunction collectSource", " updateSourceUI();\n splashMarkResult(kind);\n};\nfunction collectSource",1)
s=s.replace("if(cmVal)cmVal.textContent=cm?.ok&&cm.h24!=null?numv(cm.h24,1)+' mm':'-- mm';", "if(cmVal){let local=(cm?.stations||[]).filter(x=>x.ibge===4320008&&x.h24!=null);let peak=local.reduce((b,x)=>!b||x.h24>b.h24?x:b,null);cmVal.textContent=peak?numv(peak.h24,1)+' mm':'-- mm';}")
s=s.replace("cmMeta.textContent=cm?.ok?`G2-Colonial · ${freshness('cemaden',cm).label}`:'CEMADEN · indisponível';", "cmMeta.textContent=cm?.ok?`Maior acumulado em Sapucaia · ${freshness('cemaden',cm).label}`:'CEMADEN · indisponível';")
s=s.replace("k==='cemaden'?o?.readingAt:","k==='cemaden'?o?.fileUpdatedAt:")
s=s.replace("chuvas:{title:'Chuvas e pluviômetros',subtitle:'Previsão de chuva e monitoramento de estações.',body:'O valor de chuva estimada na previsão Open-Meteo não substitui a chuva medida no pluviômetro G2-Colonial do CEMADEN. O aplicativo consulta diretamente a estação e mostra o horário da leitura.',", "chuvas:{title:'Chuvas e pluviômetros',subtitle:'Compare os acumulados por estação e município.',body:'Medições do CEMADEN, consultadas diretamente na fonte oficial. Os horários de atualização do arquivo não confirmam, por si só, que cada estação transmitiu recentemente.',")
s=s.replace("body:'A leitura da ANA é feita diretamente pela internet, na estação de São Leopoldo. Caso a fonte não responda ou forneça medição antiga, o aplicativo indicará a indisponibilidade. A ausência de leitura não indica normalidade.'", "body:'Leitura da ANA consultada pela internet na estação São Leopoldo. A ausência de leitura não indica normalidade.'")
s=s.replace("source:'Fonte: Open-Meteo / TEMPO web.'", "source:'Fonte modelada: Open-Meteo. Estação observada: Defesa Civil RS.'")
s=s.replace("<p>Leitura da estação São Leopoldo (87382000) ainda não disponível. Abra a telemetria ANA ou o TEMPO web.</p>","<p>Leitura da estação São Leopoldo (87382000) indisponível. Consulte diretamente a telemetria oficial ANA.</p>")
start=s.index(" if(which==='chuvas'){",s.index('function renderLiveDetail(which)'))
end=s.index(" if(which==='rio'){",start)
s=s[:start]+''' if(which==='chuvas'){
  if(!cm?.ok)return `<div class="white-card detail-data"><span class="source-badge error">Rede CEMADEN indisponível</span><p>Não foi possível carregar a rede de pluviômetros. Sem dados não significa chuva zero.</p></div>`;
  const s=freshness('cemaden',cm),all=cm.stations||[];
  const own=all.filter(x=>x.ibge===4320008),valid=own.filter(x=>x.h24!==null&&Number.isFinite(Number(x.h24)));
  const peak=valid.reduce((b,x)=>!b||x.h24>b.h24?x:b,null);
  return `<div class="white-card detail-data"><span class="source-badge ${s.level==='on'?'':'stale'}">${str(cm.cached?'Último arquivo salvo':s.label)}</span><h2>Chuva medida · CEMADEN</h2>
  <p>24 horas acumuladas por estação, sem somar pluviômetros. Sapucaia: ${own.length} estações cadastradas, ${valid.length} com valores numéricos no arquivo.</p>
  <div class="metric-block">${metric('Maior acumulado em Sapucaia',peak?numv(peak.h24,1)+' mm':'--')}${metric('Estação com maior chuva',peak?.name||'Indisponível')}</div>
  <p><b>Arquivo atualizado:</b> ${str(fmtReading(cm.fileUpdatedAt))}${cm.cached?' · dados salvos':''}. A atualização do arquivo não assegura transmissão recente de cada sensor.</p>
  <div class="rain-toolbar"><button data-rain-region="sapucaia" class="rain-pill ${rainScope==='sapucaia'?'active':''}">Sapucaia</button><button data-rain-region="regiao" class="rain-pill ${rainScope==='regiao'?'active':''}">Região</button><button data-rain-region="rs" class="rain-pill ${rainScope==='rs'?'active':''}">Todo RS</button></div>
  <input id="rain-search" class="rain-search" type="search" placeholder="Buscar cidade ou estação" value="${str(rainSearch)}" aria-label="Buscar estação" />
  <div id="rain-summary" class="rain-summary"></div><div class="rain-list" id="rain-list"></div>
  <div id="rain-selected"></div>
  <p class="source-caption">Fonte: CEMADEN. Selecione uma estação para consultar o histórico horário, os acumulados em 1, 6, 12 e 24 horas e a data efetiva da leitura.</p></div>`;
 }
'''+s[end:]
s=s.replace("$('#detail-content').innerHTML=`<div class=\"detail-hero\"", "$('#detail-content').innerHTML=`<div class=\"detail-hero\"")
s=s.replace("<p class=\"source-caption\">${d.source}</p>`;\n}", "<p class=\"source-caption\">${d.source}</p>`;\n if(which==='chuvas'){renderRainList();renderRainSelected();}\n}",1)
# Additional functions inserted before startSensorMonitoring
anchor='function startSensorMonitoring(){'
newfunc='''const REGION_CITIES=new Set(['SAPUCAIA DO SUL','SÃO LEOPOLDO','ESTEIO','CANOAS','NOVO HAMBURGO','CAMPO BOM','PORTÃO','ESTÂNCIA VELHA','NOVA SANTA RITA']);
function rainList(){
 let list=sources.cemaden?.stations||[];
 if(rainScope==='sapucaia')list=list.filter(x=>x.ibge===4320008);
 if(rainScope==='regiao')list=list.filter(x=>REGION_CITIES.has(String(x.city).toUpperCase()));
 const query=rainSearch.trim().toLocaleLowerCase('pt-BR');
 if(query)list=list.filter(x=>(x.city+' '+x.name+' '+x.code).toLocaleLowerCase('pt-BR').includes(query));
 return list.slice().sort((a,b)=>(b.h24==null?-Infinity:Number(b.h24))-(a.h24==null?-Infinity:Number(a.h24))||a.city.localeCompare(b.city));
}
function renderRainList(){
 const box=$('#rain-list'),info=$('#rain-summary');if(!box||!info)return;
 const list=rainList(),valid=list.filter(x=>x.h24!==null&&Number.isFinite(Number(x.h24))),max=Math.max(1,...valid.map(x=>Number(x.h24)));
 info.textContent=`${list.length} estação(ões) · ${valid.length} com valor para 24h · em ordem de maior chuva`;
 box.innerHTML=list.length?list.map(s=>`<button class="rain-station" data-station-id="${s.id}"><span><strong>${str(s.name)}</strong><small>${str(s.city)} · ${str(s.code)}</small><div class="rain-bar"><span style="width:${s.h24==null?0:Math.round(Math.min(100,100*Number(s.h24)/max))}%"></span></div></span><span class="rain-mm ${s.h24==null?'missing':''}">${s.h24==null?'Sem dado':numv(s.h24,1)+' mm'}</span></button>`).join(''):'<p style="padding:15px">Nenhuma estação encontrada para este filtro.</p>';
}
function selectRainStation(id){
 const s=(sources.cemaden?.stations||[]).find(x=>String(x.id)===String(id));if(!s)return;
 selectedRainId=String(id);rainStationPending=true;renderRainSelected();
 if(!demo&&window.TEMPO_NATIVE)window.TEMPO_NATIVE.fetchData('cemaden_station:'+selectedRainId);
}
function renderRainSelected(){
 const box=$('#rain-selected');if(!box)return;
 if(!selectedRainId){box.innerHTML='<p class="source-caption">Toque em uma estação para ver o histórico detalhado.</p>';return;}
 const st=(sources.cemaden?.stations||[]).find(x=>String(x.id)===selectedRainId),d=rainStationDetails[selectedRainId];
 if(!st){box.innerHTML='';return;}
 if(!d?.ok){box.innerHTML=`<div class="white-card detail-data"><h2>${str(st.name)} — ${str(st.city)}</h2><p>${rainStationPending?'Consultando histórico horário no CEMADEN...':'Histórico ainda não disponível; tente novamente selecionando a estação.'}</p><p>Chuva 24h no arquivo geral: ${st.h24==null?'indisponível':numv(st.h24,1)+' mm'}</p></div>`;return;}
 const age=Date.now()-Number(d.readingTs),stale=!Number.isFinite(age)||age>2*3600000||age< -3600000;
 const bars=(d.series||[]).map(x=>x.mm===null?null:Number(x.mm));const max=Math.max(1,...bars.filter(x=>x!==null));
 box.innerHTML=`<div class="white-card detail-data"><h2>${str(st.name)} · ${str(st.city)}</h2><span class="source-badge ${stale?'stale':''}">${stale?'Medição antiga':'Histórico consultado'}</span>
 <p>Estação ${str(d.code||st.code)} · Última hora válida: ${str(fmtReading(d.readingAt))}. Dados horários em UTC, convertidos para Brasília.</p>
 <div class="metric-block">${metric('1 hora',d.h1==null?'--':numv(d.h1,1)+' mm')}${metric('6 horas',d.h6==null?'Incompleto':numv(d.h6,1)+' mm')}${metric('12 horas',d.h12==null?'Incompleto':numv(d.h12,1)+' mm')}${metric('24 horas',d.h24==null?'Incompleto':numv(d.h24,1)+' mm')}</div>
 <div class="rain-hour-bars" title="Últimas 24 horas, lacunas indicadas em cinza">${bars.map(x=>`<div class="rain-hour-col ${x===null?'missing':''}"><span style="--barheight:${x===null?12:Math.max(2,Math.min(70,x/max*70))}px"></span></div>`).join('')}</div>
 <p>Histórico das últimas 24 horas: cada coluna representa uma hora. Cinza indica ausência de leitura. Acumulados incompletos não são exibidos como zero.</p>
 <button class="source-refresh" data-station-id="${st.id}">Atualizar esta estação ${icon('refresh')}</button></div>`;
}
'''
s=s.replace(anchor,newfunc+anchor)
s=s.replace("window.TEMPO_OnNativeData=(kind,json)=>{", "window.TEMPO_OnNativeData=(kind,json)=>{")
# Rain event delegation in init
s=s.replace("function init(){", """function init(){
 $('#detail-content').addEventListener('click',ev=>{
  const region=ev.target.closest('[data-rain-region]');if(region){rainScope=region.dataset.rainRegion;renderDetail('chuvas');return;}
  const station=ev.target.closest('[data-station-id]');if(station){selectRainStation(station.dataset.stationId);return;}
 });
 $('#detail-content').addEventListener('input',ev=>{if(ev.target.id==='rain-search'){rainSearch=ev.target.value;renderRainList();}});
""")
# Splash funcs
s=s.replace("function init(){", """let splashOpenTime=Date.now(),splashCompleted=false,splashSeen=new Set();
function hideSplash(){if(splashCompleted)return;splashCompleted=true;$('#boot-splash')?.classList.add('hidden');}
function splashMarkResult(kind){splashSeen.add(kind);const note=$('#boot-status');if(note)note.textContent=`Consultando estações e avisos · ${splashSeen.size}/4 fontes retornaram`;if(splashSeen.size>=3 && Date.now()-splashOpenTime>=900)hideSplash();}
function init(){""")
s=s.replace("if(demo)displayDemo();else {updateWeather();startSensorMonitoring();}", """splashOpenTime=Date.now();setTimeout(hideSplash,3800);
 $('#boot-skip')?.addEventListener('click',hideSplash);
 if(demo){displayDemo();setTimeout(hideSplash,900);}else {updateWeather();startSensorMonitoring();}
""")
s=s.replace("function goto(target){if(['radar'", "function goto(target){if(['radar'")
js.write_text(s)

# Update metadata
b=p/'app/build.gradle';s=b.read_text().replace('versionCode 3','versionCode 4').replace("versionName '0.2.1'","versionName '0.3.0'");b.write_text(s)
r=p/'README.md';s=r.read_text()+"\n\n## 0.3.0 — Splash + comparativo CEMADEN\nTela de abertura com ícone TEMPO original e identificação tipográfica IAntzen Tecnologia. Na inicialização, as fontes são consultadas imediatamente sem bloquear o usuário além de 3,8 segundos. Chuva: arquivo oficial CEMADEN 24h (rede RS), filtros Sapucaia/região/RS e histórico individual sob demanda. A estação pode apresentar atraso mesmo que o arquivo geral seja recente.\n";r.write_text(s)
print('patched',p)