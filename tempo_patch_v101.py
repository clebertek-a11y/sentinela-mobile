from pathlib import Path
root=Path("tempo")
bridge=root/"app/src/main/java/br/com/iantzen/tempo/MonitorBridge.java"
s=bridge.read_text(encoding="utf-8")
before="        JSONArray doc=new JSONArray(body);"
after=r'''        // O feed do CEMADEN pode retornar JSONP como estacoes([{...}]).
        // Extrair o array de dados SEM modificar os acumulados medidos.
        String data = body.trim();
        if (data.startsWith("\uFEFF")) data = data.substring(1).trim();
        JSONArray doc;
        if (data.startsWith("[")) {
            doc = new JSONArray(data);
        } else if (data.startsWith("{")) {
            doc = new JSONArray().put(new JSONObject(data));
        } else {
            int call = data.indexOf('(');
            int begin = data.indexOf('[', Math.max(0, call));
            int end = data.lastIndexOf(']');
            if (call < 0 || begin < call || end <= begin)
                throw new Exception("Resposta CEMADEN não reconhecida");
            doc = new JSONArray(data.substring(begin, end + 1));
        }'''
assert s.count(before)==1, "Não encontrou parser antigo"
s=s.replace(before,after)
s=s.replace("TEMPO-Sapucaia-Android/0.3","TEMPO-Sapucaia-Android/1.0.1")
bridge.write_text(s,encoding="utf-8")
app=root/"app/src/main/assets/app.js"
j=app.read_text(encoding="utf-8")
j=j.replace("0.3.0","1.0.1")
app.write_text(j,encoding="utf-8")
h=root/"app/src/main/assets/index.html"
h.write_text(h.read_text(encoding="utf-8").replace("Versão piloto 0.3.0","Versão 1.0.1").replace("Versão 1.0.0","Versão 1.0.1"),encoding="utf-8")
g=root/"app/build.gradle"
t=g.read_text(encoding="utf-8")
assert "versionCode 4" in t and "versionName '0.3.0'" in t, "Versão base inesperada"
t=t.replace("versionCode 4","versionCode 10001").replace("versionName '0.3.0'","versionName '1.0.1'")
g.write_text(t,encoding="utf-8")
print("CEMADEN parser JSON/JSONP, TEMPO 1.0.1 corrigidos")
