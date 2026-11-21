# SIGADE CENTRAL — Integração Android em homologação

Esta ramificação é **experimental**. Não altera o APK 0.5.0 existente, não publica uma atualização e não migra automaticamente o banco legado do SENTINELA.

## Como funciona

- O Android terá uma opção **SIGADE CENTRAL · compartilhado** no painel.
- **SIGADE Web e SIGADE CENTRAL** usarão o mesmo banco Supabase e o UUID oficial da ocorrência.
- O usuário entrará com **e-mail/senha central do SIGADE**, diferente da senha local do Android. A autorização de administrador, operador ou agente depende dos papéis validados no servidor.
- O app central mantém cache e fila SQLite separados do banco antigo, com UUID de operação imutável (idempotência), número provisório apenas até confirmação e controle de versão para rejeitar sobrescrita.
- Alterações na Web entram no feed do servidor e aparecem no Android com atualização manual ou consulta de 20 em 20 segundos enquanto a tela está aberta; ao conectar, também haverá reconciliação paginada (snapshot).
- **Registro**: o operador pode preencher o chamado no Android, inclusive sem internet. O número oficial só aparece após a confirmação do banco SIGADE.
- **Deslocamento**: o comando “A caminho” exige ocorrência sincronizada, versão atual e autorização do servidor.
- **BA, fotografias, conclusão e decisão administrativa**: continuam no fluxo do SIGADE Web, aberto pelo navegador com login próprio. A versão nativa local do BA **não foi migrada** e não deve criar uma segunda ocorrência central.
- **SERVIDOR02**: permanece receptor dos pacotes finais gerados pelo backend do SIGADE; o Android não envia ZIP diretamente para esse mecanismo central.

## Contrato esperado do servidor

Projeto SIGADE Lovable: docs/sql/sigade-mobile-sync-pendente.sql (somente quando preparado e homologado).

PostgREST com Bearer JWT de Supabase Auth + chave *publishable/anon*:

- `POST /rest/v1/rpc/mobile_comando` com `p_operacao_id`, `p_tipo` = criar/status, `p_ocorrencia_id`, `p_base_versao`, `p_dados`. Resposta JSON com `registro` e `mobile_versao`. Mesmo UUID de comando deve devolver a mesma resposta; base_version divergente deve retornar erro `MOBILE_CONFLITO`.
- `POST /rest/v1/rpc/mobile_mudancas` com `p_apos`, `p_limite`. Resposta `itens: [{seq, ocorrencia, removida}]`, `proximo`, `has_more`. O cliente consulta uma sobreposição de 100 eventos, porque sequências criadas antes de commit podem chegar fora de ordem.
- `POST /rest/v1/rpc/mobile_snapshot` com `p_depois` (UUID ou null) e `p_limite`. Resposta `itens` com ocorrências completas, `proximo`, `has_more`. Reconciliação periódica evita depender exclusivamente do feed.

**Não coloque service_role, senhas, JWTs ou chave de assinatura no GitHub, APK ou comandos de build.** A URL Supabase e a chave publishable não são segredos, mas devem ser conferidas pelo administrador.

## Compilar sem alterar o aplicativo existente

Ao extrair `sentinela_source_payload.zip` no job de homologação:

```sh
python3 patches/central-sync/ativar_central.py
gradle --no-daemon :app:assembleDebug
```

O patch usa `applicationId = br.gov.rs.sapucaiadosul.sentinela.atualizavel.homologacao`, que instala em paralelo com o APK existente. Nunca use esta build com ocorrências reais antes da homologação.

## Cuidados e limitações

1. As ocorrências criadas no **modo local antigo** não são compartilhadas automaticamente; isso evita subir testes ou dados incompletos e duplicar números. A migração dessas ocorrências exige ferramenta e reconciliação futura, registro a registro.
2. A fila local do modo central é durável e reaproveita o mesmo UUID em cada retry. O app **não marca documentos como arquivados no SERVIDOR02** quando apenas sincronizados com a nuvem.
3. Conflitos são preservados para análise humana, sem sobrescrita silenciosa. O usuário ainda não tem uma tela de resolução detalhada.
4. Cache e pendências SQLite **ainda não são cifrados**. Sessões JWT são protegidas pelo Android Keystore, mas o aplicativo continua em homologação e não deve receber dados pessoais operacionais até revisão de criptografia, backup, papéis e revogação.
5. O operador deve validar separadamente login, permissões de agente designado, foto GPS, assinatura, decisão por EA, exportação ao SERVIDOR02 e fluxo offline em dispositivo físico. Nenhum teste com dados reais foi executado.
6. Os dispositivos podem receber eventos com atraso de rede; a tela aberta consulta a cada ~20 segundos. Atualização instantânea por WebSocket/Realtime nativo pode ser acrescentada depois da aprovação do protocolo, sem mudar o banco.
7. Não desinstale o aplicativo atual enquanto houver atendimentos locais não exportados e confirmados.
