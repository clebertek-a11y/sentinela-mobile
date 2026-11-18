# SENTINELA Mobile 0.6.0 — integração com HUB (piloto)

## Instalação de teste
Esta edição usa o identificador `br.gov.rs.sapucaiadosul.sentinela.atualizavel.hubpiloto`, assinado com a chave de depuração do GitHub Actions. Ela **não atualiza nem apaga** a versão 0.5.0 instalada. Os bancos de dados são independentes. Não desinstale o aplicativo anterior nem limpe seus dados.

O ícone enviado em 18/11/2026 está incorporado no launcher, na tela de login e no cabeçalho da central.

## Preparação do SERVIDOR02
1. Instalar e ativar Sentinela HUB 1.0.0.
2. Criar uma conta **operador** específica para o teste e confirmar o compartilhamento SMB `\\servidor02\Comp\SENTINELA`.
3. Configurar HTTPS com certificado confiável. Para teste via internet móvel, usar domínio HTTPS publicado pelo túnel, sem abrir a porta 8787 no roteador.
4. Confirmar `GET https://SEU-DOMINIO/api/v1/health`; o Android exige HTTPS válido.

## Configurar Android
1. Instalar o APK piloto em paralelo e criar acesso local de teste.
2. Ir a **Configurações → Conexão com Sentinela HUB**.
3. Informar somente a URL base, por exemplo `https://hub.seudominio.com.br` (não acrescentar `/api/v1`).
4. Informar nome de usuário e senha criados no HUB. A senha fica protegida com Android Keystore.
5. Salvar, depois tocar em **Testar conexão e sincronizar**.
6. Criar uma ocorrência fictícia. No painel do HUB verificar a coleção `ocorrencias`; comparar número, endereço e status.
7. Em outro cliente autenticado, alterar essa ocorrência e verificar se a atualização chega ao Android com **Sincronizar agora**.

## Funcionalidades implementadas
- Login no HUB por `POST /api/v1/auth/login`; o app não utiliza token expirável gravado em claro.
- `POST /api/v1/sync/push` com versão de base e ID de mutação persistente para reenvio sem duplicação.
- `GET /api/v1/sync/pull` incremental; cursor salvo após gravação local.
- Banco local preservado; fila de pendências marcada `PENDENTE`.
- Conflitos `409` são sinalizados como `CONFLITO`, sem sobrescrever alterações locais.
- Envio de fotos e PDFs com deduplicação por SHA-256, respeitando limite de 16 MB do HUB.
- Tarefas periódicas de rede do Android (~15 minutos) e sincronização manual pelo botão do painel.
- Importação de campos de ocorrência e textos do boletim sem zerar campos locais ausentes no pacote remoto.

## Limitações conhecidas
- O piloto NÃO deve ser usado com dados reais antes da homologação.
- Atualização visual em tempo real no Windows requer que o **cliente Windows** também implemente a mesma API. O HUB funciona como intermediário; instalar apenas este APK não modifica o cliente Windows.
- A distribuição de uma atualização que substitua a 0.5.0 depende da configuração dos segredos de assinatura `SENTINELA_KEYSTORE_BASE64` e `SENTINELA_KEYSTORE_PASSWORD`, com o mesmo certificado da versão existente. Os segredos estão ausentes na conta GitHub atual.
- Arquivos anexados pelo HUB não são baixados automaticamente para armazenamento local do Android; os metadados e anexos ficam no HUB. 
- Conflitos precisam de decisão humana; ainda não há tela completa de conciliação.
- Android pode adiar tarefas em segundo plano por restrições de bateria. Para validar imediatamente, use o botão manual.
- Use cópia de segurança de atendimentos antes de qualquer migração para aplicativo de produção.

## Testes que faltam no SERVIDOR02
Wi-Fi na base, internet móvel externa, login, push/pull entre dois aparelhos, reinício do servidor, perda temporária de conexão, conflito concorrente, upload de fotos/PDF, permissões SMB e restauração de backup.
