from pathlib import Path
import sys
root=Path(sys.argv[1]) if len(sys.argv)>1 else Path("tempo")
assets=root/"app/src/main/assets"
h=assets/"index.html"
s=h.read_text(encoding="utf-8")
old='<strong id="river-value">-- m</strong><small id="river-meta">ANA · São Leopoldo · aguardando leitura</small>'
new='<strong id="river-value">-- m</strong><span id="river-trend" class="river-trend unavailable" aria-live="polite">↔ Tendência indisponível</span><small id="river-meta">ANA · São Leopoldo · aguardando leitura</small>'
assert s.count(old)==1,"Cartão do Rio dos Sinos inesperado"
h.write_text(s.replace(old,new).replace("Versão 1.0.1","Versão 1.0.2"),encoding="utf-8")
css=assets/"style.css"
s=css.read_text(encoding="utf-8")
s+="""
/* TEMPO 1.0.2 — tendência fluvial na tela inicial. */
.stat-card .river-trend{display:inline-flex;align-items:center;max-width:100%;font-size:11px;font-weight:800;line-height:1.35;padding:4px 7px;border-radius:9px;white-space:normal}
.river-trend.up{background:#ffe8e6;color:#a22624}
.river-trend.down{background:#ddf8e8;color:#136942}
.river-trend.stable{background:#e7effe;color:#154b93}
.river-trend.unavailable{background:#edf1f7;color:#435a76}
[data-theme="dark"] .river-trend.up{background:#5c252e;color:#ffb6a9}
[data-theme="dark"] .river-trend.down{background:#183f39;color:#a9f8c1}
[data-theme="dark"] .river-trend.stable{background:#294573;color:#dceeff}
[data-theme="dark"] .river-trend.unavailable{background:#293952;color:#cfdeef}
"""
css.write_text(s,encoding="utf-8")
js=assets/"app.js"
s=js.read_text(encoding="utf-8")
needle="function updateSourceUI(){"
assert s.count(needle)==1
func="""// Tendência calculada apenas com as leituras de nível da ANA (São Leopoldo, 87382000).
function riverTrendInfo(an){
 const unavailable=(reason='Sem medições suficientes')=>({state:'unavailable',text:'↔ Tendência indisponível',reason});
 if(!an?.ok)return unavailable('ANA não respondeu');
 if(an.cached)return unavailable('Últimos dados salvos, sem consulta recente');
 if(freshness('ana',an).level!=='on')return unavailable('Leitura antiga ou sem horário válido');
 const levels=(Array.isArray(an.series)?an.series:[]).filter(x=>x&&x.level!==null&&x.level!==undefined).map(x=>({t:msFromReading(x.time),m:Number(x.level)}))
  .filter(x=>Number.isFinite(x.t)&&Number.isFinite(x.m)&&x.m>=0).sort((a,b)=>a.t-b.t);
 if(levels.length<2)return unavailable();
 const last=levels[levels.length-1],age=Date.now()-last.t;
 if(age < -60000 || age>3*3600000)return unavailable('Nível não medido recentemente');
 const target=last.t-3*3600000;
 const previous=levels.filter(x=>x.t<=last.t-3600000&&x.t>=last.t-6*3600000)
  .sort((a,b)=>Math.abs(a.t-target)-Math.abs(b.t-target))[0];
 if(!previous)return unavailable('Histórico insuficiente para comparar pelo menos uma hora');
 const hours=(last.t-previous.t)/3600000,rate=(last.m-previous.m)*100/hours;
 if(!Number.isFinite(rate))return unavailable();
 const state=rate>0.5?'up':rate< -0.5?'down':'stable';
 const text=state==='up'?'↑ Subindo '+numv(Math.abs(rate),1)+' cm/h':state==='down'?'↓ Descendo '+numv(Math.abs(rate),1)+' cm/h':'→ Estável';
 return {state,text,rateCmH:rate,spanHours:hours,levelM:last.m,readingTime:last.t,reason:'Comparação em '+numv(hours,1)+' h'};
}
"""
s=s.replace(needle,func+needle)
old="let rm=$('#river-meta');"
new="const rt=riverTrendInfo(an),trendEl=$('#river-trend');\n if(trendEl){trendEl.className='river-trend '+rt.state;trendEl.textContent=rt.text;trendEl.title=rt.reason;}\n "+old
assert s.count(old)==1,"Atualização do cartão não encontrada"
s=s.replace(old,new)
old="numv(an.flowM3s,1)+' m³/s')}"
new=old+"$"+"{metric('Tendência',riverTrendInfo(an).text,riverTrendInfo(an).reason)}"
assert s.count(old)==1,"Detalhamento do Rio não encontrado"
s=s.replace(old,new)
js.write_text(s,encoding="utf-8")
g=root/"app/build.gradle"
s=g.read_text(encoding="utf-8")
assert "versionCode 10001" in s and "versionName '1.0.1'" in s
g.write_text(s.replace("versionCode 10001","versionCode 10002").replace("versionName '1.0.1'","versionName '1.0.2'"),encoding="utf-8")
b=root/"app/src/main/java/br/com/iantzen/tempo/MonitorBridge.java"
b.write_text(b.read_text(encoding="utf-8").replace("TEMPO-Sapucaia-Android/1.0.1","TEMPO-Sapucaia-Android/1.0.2"),encoding="utf-8")
print("TEMPO 1.0.2: indicador de tendencia do Rio dos Sinos aplicado")
