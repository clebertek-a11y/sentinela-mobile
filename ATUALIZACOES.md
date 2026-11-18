# Atualizações automáticas — SENTINELA Mobile

O aplicativo compara a versão instalada ao catálogo HTTPS disponível em `updates/android-estavel.json`. Ao abrir com internet, avisa sobre nova versão e oferece `Atualizar agora` e `Depois`. Sem internet mantém o funcionamento offline. O Android sempre pede confirmação para instalar um APK.

O canal atualizável usa `br.gov.rs.sapucaiadosul.sentinela.atualizavel`, distinto dos APKs anteriores, porque eles foram assinados com chaves temporárias incompatíveis. A primeira instalação desse canal não migra automaticamente o banco de dados de aplicativos anteriores; mantenha o aplicativo antigo até exportar os registros.

Para todas as futuras versões usaremos a mesma chave de assinatura. A chave fica fora do repositório; o responsável autorizado deve configurar em **Settings > Secrets and variables > Actions** os segredos `SENTINELA_KEYSTORE_BASE64` e `SENTINELA_KEYSTORE_PASSWORD` com os valores do pacote privado de assinatura entregue separadamente. **Não envie o arquivo da chave nem a senha aos arquivos deste repositório público.**

Uma vez configurados os segredos, executar manualmente **Actions > Publicar APK atualizavel > Run workflow**. O processo compila, assina, publica o APK e atualiza o catálogo. Nas versões posteriores, aumentar `versionCode` e `versionName` no script de preparação; manter o mesmo identificador de pacote e a mesma chave. O código é piloto, não homologado para ocorrências reais.
