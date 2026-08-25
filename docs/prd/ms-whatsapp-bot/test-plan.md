# Test Plan — ms-whatsapp-bot (POST /webhook/evolution)

## Contexto do serviço

- Único endpoint público: `POST /webhook/evolution` (porta `8083`, sem autenticação — `SecurityConfig` faz `permitAll()` em `/webhook/**`).
- O endpoint **sempre responde 200** quando o payload é sintaticamente válido, independente do resultado do processamento interno (comentário no código: "Sempre retorna 200 para a Evolution API não retentar o envio"). Processamento roda em virtual thread, fora do ciclo request/response.
- Consequência direta pro teste: **o status HTTP não prova nada sobre o resultado**. A prova real está no estado da sessão em Redis (`whatsapp:sessao:<numero>`, TTL 30 min) e nos logs da aplicação (nível DEBUG habilitado em dev).
- Dependências externas reais usadas pelo fluxo: Redis (`redis-sgsm`, porta 6379), Evolution API (`localhost:8090`), `sgsm-ia` (`localhost:8082`, classificação de intenção via RAG), `sgsm` (`localhost:8080`, criação de paciente/agendamento), `ms-sboot-auth` (`localhost:8081`, registro/OTP).
- Número de teste usado em todos os itens: `5511900000001` (fictício, não vinculado a WhatsApp real — evita disparo de mensagem pra número de terceiro; a prova de "resposta enviada" vem do log de `EvolutionApiClient.enviarTexto` e/ou da chamada real que ele faz pro Evolution API real em `:8090`, não da entrega final no aparelho).
- Evidência de banco: `docker exec redis-sgsm redis-cli GET "whatsapp:sessao:5511900000001"` antes/depois de cada requisição relevante.

Payload base (evento de mensagem individual válido):
```json
{
  "event": "messages.upsert",
  "instance": "sgsm-bot",
  "data": {
    "key": { "remoteJid": "5511900000001@s.whatsapp.net", "fromMe": false, "id": "TESTID001" },
    "pushName": "QA Tester",
    "message": { "conversation": "<texto aqui>" },
    "messageTimestamp": "1700000000"
  }
}
```

---

## A. Filtragem de eventos no controller (não deve chamar o orquestrador)

- [x] **WB001** — `event` diferente de `messages.upsert` (ex.: `"connection.update"`) → 200, sem alteração na sessão Redis, sem log de "Webhook recebido de".
- [x] **WB002** — `data` ausente (`null`) → 200, sem efeito colateral.
- [x] **WB003** — `data.key` ausente → 200, sem efeito colateral.
- [x] **WB004** — `data.key.fromMe = true` (mensagem enviada pelo próprio bot) → 200, sessão não é criada/alterada.
- [x] **WB005** — `remoteJid` de grupo (termina em `@g.us`, ex.: `"120363000000000000@g.us"`) → 200, sessão não é criada.
- [x] **WB006** — `remoteJid` ausente/`null` → 200, sem efeito.
- [x] **WB007** — mensagem sem texto (`data.message` ausente, ou `conversation` e `extendedTextMessage` ambos ausentes) → 200, sem efeito.
- [x] **WB008** — texto em branco (`"conversation": "   "`) → 200, sem efeito (checagem `isBlank()`).
- [x] **WB009** — texto via `extendedTextMessage.text` em vez de `conversation` (mensagem com preview de link) → processado normalmente, mesmo comportamento de WB010.

## B. Consentimento LGPD (primeiro contato)

- [x] **WB010** — primeira mensagem de um número novo, texto qualquer (ex.: `"oi"`) → sessão criada no Redis com `consentimentoAceito: false`; log/tentativa de envio da mensagem de consentimento (`bot.consentimento.mensagem`).
- [x] **WB011** — mesmo número, envia `"ACEITO"` → sessão atualizada para `consentimentoAceito: true`; tentativa de envio do menu principal (sem token: opções 1-Agendar, 2-Cadastrar, 3-Info médicos).
- [x] **WB012** — variação de caixa `"aceito"` / `"  ACEITO  "` (com espaços) → também aceito (`equalsIgnoreCase` + `trim()`).
- [x] **WB013** — número novo envia algo diferente de `"ACEITO"` (ex.: `"não quero"`) → sessão continua com `consentimentoAceito: false`, reenvia mensagem de consentimento (loop esperado até aceitar).

## C. Classificação de intenção / conversa (RAG via sgsm-ia)

Pré-condição: sessão com `consentimentoAceito: true` (rodar WB010+WB011 antes, ou popular a sessão direto no Redis).

- [x] **WB020** — pergunta genérica (ex.: `"Quais especialidades vocês atendem?"`) → intent `CONSULTAR` ou `CONVERSAR`; sessão grava histórico (`USUARIO` + `ASSISTENTE`) respeitando `historicoMax=10`.
- [x] **WB021** — envia 12 mensagens seguidas nessa sessão → confirma que `historico` no Redis nunca ultrapassa 10 entradas (mais antigas são descartadas).
- [x] **WB022** — pergunta sobre agendar sem estar autenticado (ex.: `"quero marcar uma consulta"`) → resposta pede e-mail cadastrado, `accessToken` continua `null`, **nenhuma** `confirmacaoPendente` é criada (código só monta payload quando já há dados completos e confirmação). Corrigido e re-testado, ver `evidencias/WB022-retest.md`.
- [x] **WB023** — pergunta sobre cancelar sem estar autenticado → resposta pede e-mail, sem `confirmacaoPendente`. Corrigido e re-testado, ver `evidencias/WB023-retest.md`.

## D. Fluxo de autenticação/cadastro (OTP)

- [x] **WB030** — intenção `CADASTRAR` com dados completos e confirmação (ex.: fluxo guiado informando nome, CPF, data nascimento e e-mail em mensagens sucessivas) → ao confirmar, service chama `sgsmClient.criarPaciente` (real, `:8080`) e `authClient.registrar` (real, `:8081`); sessão passa a ter `confirmacaoPendente.tipo = "OTP"` com o e-mail salvo. **Corrigido e re-testado, ver evidencias/WB030-retest2.md**: correção na branch `CONFIRMAR` de `BotOrquestradorService.processar()` (só zera `confirmacaoPendente` se o tipo não tiver mudado) preservou a transição para `"OTP"`. Confirmado com número novo (`5511900000033`): `confirmacaoPendente.tipo` chegou a `"OTP"` com o e-mail correto, paciente real criado no `sgsm` e conta real criada no `ms-sboot-auth`.
- [x] **WB031** — CPF ou e-mail já cadastrado no `sgsm` → resposta de erro amigável ("Verifique se o CPF ou e-mail já estão cadastrados"), sessão não trava em estado inconsistente (`confirmacaoPendente` permanece nulo, dá pra tentar de novo). **Corrigido e re-testado, ver `evidencias/WB031-retest.md`**: com a máscara de PII corrigida, foi possível isolar a checagem de duplicidade — CPF duplicado é rejeitado pelo `sgsm` com erro específico de negócio (`400 "CPF já cadastrado"`, não mais o erro de formato mascarado), nenhum paciente duplicado é criado, sessão retorna a `null` e permite nova tentativa.
- [x] **WB032** — com `confirmacaoPendente.tipo = "OTP"` pendente, envia código correto (peça o OTP real gerado no passo WB030, verificado via `authClient`) → sessão recebe `accessToken`/`refreshToken`/`perfil`, `confirmacaoPendente` volta a `null`, resposta de sucesso.
- [x] **WB033** — com OTP pendente, envia código errado (ex.: `"000000"`) → resposta "Código incorreto ou expirado", sessão mantém `confirmacaoPendente` (permite nova tentativa), **não** grava `accessToken`.
- [x] **WB034** — com OTP pendente, envia código já expirado (esperar o TTL do OTP no `ms-sboot-auth`, se configurável em ambiente de teste, ou usar um código de uma sessão OTP anterior) → mesma resposta de erro que WB033 (mensagem não diferencia "errado" de "expirado", ambos caem no mesmo catch — documentar como achado, não como bug bloqueante).

## E. Confirmação / recusa de ação pendente

- [x] **WB040** — envia `"confirmo"` (intent `CONFIRMAR`) sem nenhuma `confirmacaoPendente` na sessão → resposta "Não há nenhuma ação pendente para confirmar.", sem erro 500.
- [x] **WB041** — com `confirmacaoPendente.tipo = "AGENDAR"` válido (dados de médico/serviço/data/hora reais do `sgsm`), confirma → `executorAcaoService.executarAgendamento` roda de verdade contra `:8080`; conferir no Postgres do `sgsm` (ou via `GET /v1/api/agendamentos`) que o agendamento foi criado; `confirmacaoPendente` volta a `null`. **Corrigido e re-testado, ver evidencias/WB041-retest.md**: `processarOtp()` agora seta `sessao.usuarioId` via `GET /v1/api/auth/me`, e `ExecutorAcaoService.combinarDataHora()` normaliza `dd/MM/yyyy`/`yyyy-MM-dd` para `OffsetDateTime` ISO com fuso `America/Sao_Paulo`. Agendamento real criado (`pacienteId` correto, `dataHoraInicio` ISO válido), `confirmacaoPendente` volta a `null`.
- [x] **WB042** — com `confirmacaoPendente.tipo = "CANCELAR"` apontando pra um `agendamentoId` inexistente → tratamento de erro sem 500 não tratado, resposta de erro coerente pro usuário. (Achado adicional registrado em `evidencias/WB042.md`: o cancelamento real falha com 400 mesmo para IDs válidos, por ausência de corpo/Content-Type na chamada — fora do escopo estrito deste item, mas relevante.)
- [x] **WB043** — envia `"não"` / `"cancela"` (intent `RECUSAR`) com uma `confirmacaoPendente` ativa → `confirmacaoPendente` é limpo (`null`) sem executar a ação, resposta volta ao menu principal.

## F. Autenticação/autorização do próprio webhook (achado de segurança a validar)

- [x] **WB050** — `bot.evolution.webhook-secret` está configurado (valor: `[REDIGIDO]`) mas **não há nenhuma validação dele no `WebhookController`**. Confirmar isso batendo no endpoint sem nenhum header de assinatura/segredo e comprovando que o payload é processado normalmente (200 + efeito no Redis). **Se confirmado, reportar como achado de segurança na entrega (Fase 5)**, não como item "falho bloqueante" do fluxo funcional — é um risco de arquitetura, não um bug de comportamento. **CONFIRMADO** — ver `evidencias/WB050.md`.
- [x] **WB051** — repetir WB050 enviando um header arbitrário `X-Evolution-Signature: qualquercoisa` → mesmo resultado (confirma que não existe verificação, nem sequer opcional). **CONFIRMADO** — ver `evidencias/WB051.md`.

## G. Idempotência e concorrência

- [x] **WB060** — reenviar exatamente o mesmo payload de WB020 duas vezes seguidas (mesmo `id` de mensagem) → não há deduplicação por `data.key.id` no código; as duas chamadas são processadas como mensagens novas (2 entradas no histórico). Documentar como comportamento esperado (endpoint não é idempotente por design) — não é bug, mas fica registrado.
- [x] **WB061** — disparar duas requisições HTTP simultâneas para o **mesmo número**, com textos diferentes (ex.: via `curl` em background, `&` no shell, quase ao mesmo tempo) → inspecionar a sessão final no Redis: como `SessaoService.obter()`/`salvar()` não usa lock nem operação atômica, existe risco de *lost update* (uma das duas gravações sobrescrever a outra). Registrar o resultado real observado — se uma mensagem "sumir" do histórico, é um achado de concorrência a reportar. **CONFIRMADO** — ver `evidencias/WB061.md`: mensagem A desapareceu completamente do histórico.

## H. Serviço dependente indisponível

- [ ] **WB070** — com `sgsm-ia` (`:8082`) derrubado temporariamente, mandar uma mensagem que exigiria classificação → resposta "Serviço temporariamente indisponível. Tente novamente em alguns instantes.", **sem** gravar histórico da mensagem (o código retorna antes de chamar `adicionarHistorico`), sem exception não tratada (checar log). Religar o serviço depois do teste. **NÃO-TESTÁVEL** — ver `evidencias/WB070.md`: serviço roda via IDE, sem forma segura de religar de forma idêntica.
- [ ] **WB071** — com `ms-sboot-auth` (`:8081`) derrubado durante verificação de OTP pendente → cai no mesmo catch de "Código incorreto ou expirado" (mensagem enganosa pro usuário, já que na verdade é falha de infraestrutura, não código errado — mesmo achado de UX/observabilidade de WB034, documentar). **NÃO-TESTÁVEL** — ver `evidencias/WB071.md`: mesma justificativa de WB070.

## I. Contrato de erro / payloads malformados

- [x] **WB080** — corpo vazio (`""`) com `Content-Type: application/json` → Spring retorna 400 (erro de parse), formato de erro deve ser o `ProblemDetail` padrão (`spring.mvc.problemdetails.enabled=true`), não um 500 estourado.
- [x] **WB081** — JSON sintaticamente inválido (ex.: `{"event": "messages.upsert",}` com vírgula sobrando) → 400.
- [x] **WB082** — campo com tipo errado (ex.: `"fromMe": "sim"` como string em vez de boolean) → 400 (falha de deserialização Jackson), não deve derrubar o processo nem responder 200 silenciosamente.
- [x] **WB083** — `Content-Type` ausente ou `text/plain` em vez de `application/json` → Spring rejeita com 415 ou 400 (confirmar qual). **Confirmado: 415.**
- [x] **WB084** — campos extras/desconhecidos no JSON (ex.: `"foo": "bar"` na raiz) → 200, processado normalmente (`@JsonIgnoreProperties(ignoreUnknown = true)` está presente em todos os DTOs aninhados).
- [x] **WB085** — payload gigante — `conversation` com uma string de ~200KB → confirmar que não derruba o serviço nem trava a virtual thread indefinidamente; ver se há corte de tamanho antes de mandar pro `sgsm-ia` (hoje não há — documentar como achado se o serviço aceitar sem limite). **Confirmado: sem corte, achado documentado.**
- [x] **WB086** — texto com unicode/emoji (ex.: `"Oi! 😀 Quero marcar consulta às 14h30 — é possível?"`) → processado sem erro de encoding, histórico grava corretamente (conferir no Redis que os bytes não foram corrompidos).

## J. Observabilidade

- [ ] **WB090** — para qualquer mensagem válida processada, confirmar no log da aplicação (nível DEBUG) a linha `Webhook recebido de {numero}: '{texto}'` e, quando houver resposta, `Mensagem enviada para {numero}: {texto}` — evidência via `docker logs`/console da aplicação, trecho colado como prova. **NÃO-TESTÁVEL** — ver `evidencias/WB090.md`: sem log em arquivo, processo roda em console externo (IDE) inacessível a este subagente.
- [ ] **WB091** — forçar uma exceção não mapeada dentro do fluxo (ex.: número de telefone com formato that quebre alguma extração, se existir algum ponto frágil identificado durante os testes acima) → confirmar que cai no `catch (Exception e)` do controller, loga `Erro ao processar webhook: ...` com stacktrace, e **ainda assim** responde 200 (comportamento intencional documentado no código). **NÃO-TESTÁVEL** — ver `evidencias/WB091.md`: tentativas de boa-fé não reproduziram o cenário; código sincrono do controller é bem protegido contra os edge cases plausíveis via HTTP.

---

## Observações para a Fase 3 (execução)

- Popular o Redis manualmente via `redis-cli` (dentro do container `redis-sgsm`) é aceitável **apenas** para preparar pré-condição de um item (ex.: sessão já autenticada, sem repetir o fluxo de OTP inteiro toda vez) — a prova do item em si sempre precisa vir de uma chamada HTTP real ao webhook + leitura real do Redis depois, nunca de inferência de código.
- Itens que dependem de dado real do `sgsm` (médico, serviço, paciente existentes) devem usar dados já existentes no banco de dev, consultados antes via GET nos endpoints já conhecidos (`/v1/api/agendamentos`, etc.) — não inventar IDs.
- Redigir qualquer JWT, `sistema.jwt`, `webhook-secret` ou senha temporária (`senhaTemp`) que aparecer em log ou payload antes de colar como evidência.