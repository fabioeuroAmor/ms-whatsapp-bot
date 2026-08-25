# Resumo da execução — QA funcional `ms-whatsapp-bot` (POST /webhook/evolution)

Execução ao vivo contra o serviço real em `http://localhost:8083`, com Redis (`redis-sgsm`), `sgsm` (`:8080`), `ms-sboot-auth` (`:8081`), `sgsm-ia` (`:8082`) e Evolution API real (`:8090`) todos ativos. Todas as provas (requisição/resposta crua + estado de Redis antes/depois, e chamadas reais aos sistemas dependentes quando aplicável) estão em `docs/prd/ms-whatsapp-bot/evidencias/<ID>.md`. Segredos (JWTs, `senha`, `webhook-secret`) foram redigidos em todas as evidências.

Total de itens do plano: **41**.

## Contagem final

- **Aprovados: 37** — WB001-WB009, WB010-WB013, WB020-WB023, WB030-WB034, WB040-WB043, WB050-WB051, WB060-WB061, WB080-WB086.
- **Reprovados: 0**
- **Não-testáveis: 4**

## Re-teste WB030/WB031 (Fase 4, após correção do mascaramento de PII no `sgsm-ia`)

`sgsm-ia` foi corrigido (parse do JSON do LLM passou a ocorrer antes do guardrail de sanitização, que agora só mascara `respostaUsuario`, nunca `entidades.cpf`/`entidades.email`) e re-testado com números de telefone novos (`5511900000031`, `5511900000032`), não usados em nenhuma evidência anterior.

- **WB031: aprovado** (mudou de reprovado para aprovado). Ver `evidencias/WB031-retest.md`.
- **WB030: aprovado nesta segunda rodada** (após novo bug encontrado e corrigido — ver abaixo). Ver `evidencias/WB030-retest2.md`.

## Re-teste WB030/WB041 (Fase 4, segunda rodada — correção da branch `CONFIRMAR` e de `SessaoBot.usuarioId`/`combinarDataHora`)

Duas causas adicionais corrigidas em `ms-whatsapp-bot`:
1. Em `BotOrquestradorService.processar()`, case `CONFIRMAR`, a linha que zerava `confirmacaoPendente` era executada incondicionalmente, sobrescrevendo o `"OTP"` que `executarCadastro()` acabara de setar. Corrigido para só zerar se o tipo não tiver mudado.
2. `processarOtp()` nunca atribuía `SessaoBot.usuarioId`; agora chama `GET /v1/api/auth/me` com o `accessToken` obtido e seta `usuarioId` a partir de `referenciaId`. `ExecutorAcaoService.combinarDataHora()` não normalizava o formato de data; agora aceita `dd/MM/yyyy`/`yyyy-MM-dd` e monta `OffsetDateTime` ISO com fuso `America/Sao_Paulo`.

Re-testado em fluxo único contínuo, com número novo (`5511900000033`, conferido sem uso prévio em nenhuma evidência): cadastro completo → `confirmacaoPendente.tipo` chegou a `"OTP"` (WB030) → OTP real enviado pelo webhook preencheu `usuarioId` → fluxo AGENDAR completo com data em `dd/MM/yyyy` gerou agendamento real no `sgsm` com `pacienteId` correto e `dataHoraInicio` ISO válido (WB041).

- **WB030: aprovado** nesta segunda rodada. Ver `evidencias/WB030-retest2.md`.
- **WB041: aprovado** (mudou de reprovado para aprovado). Ver `evidencias/WB041-retest.md`.

## Re-teste WB022/WB023 (Fase 4, após correção do tratamento de intent sem autenticação no `sgsm-ia`)

`sgsm-ia` foi corrigido em `WhatsAppService.java`: o `SYSTEM_PROMPT` passou a instruir explicitamente o LLM a não trocar a intenção para `AUTENTICAR` só por causa do perfil não identificado, e a pedir e-mail antes de prosseguir com `AGENDAR`/`CANCELAR` quando o perfil não é identificado; além disso foi adicionado um reforço determinístico no código que sobrescreve a resposta pedindo e-mail sempre que o intent classificado for `AGENDAR`/`CANCELAR` e o perfil vier nulo, independente do que o LLM tenha respondido. Re-testado com quatro números novos (`5511900000122` a `5511900000125`, não usados em nenhuma evidência anterior), duas tentativas por item com frases diferentes, para checar a consistência do LLM (o achado original apontava comportamento não-determinístico).

- **WB022: aprovado** (mudou de reprovado para aprovado) — ambas as tentativas ("quero marcar uma consulta" e "preciso agendar com um cardiologista") produziram a mesma resposta determinística pedindo e-mail cadastrado; `accessToken`/`confirmacaoPendente` permaneceram `null` nas duas. Ver `evidencias/WB022-retest.md`.
- **WB023: aprovado** (mudou de reprovado para aprovado) — mesmo padrão, com "quero cancelar minha consulta" e "preciso cancelar meu horario marcado". Ver `evidencias/WB023-retest.md`.

## Itens reprovados (com motivo)

Nenhum item reprovado ao final desta rodada. **WB030** e **WB041** — os dois únicos itens reprovados na rodada anterior — foram corrigidos e re-testados com sucesso (ver seção "Re-teste WB030/WB041" acima, `evidencias/WB030-retest2.md` e `evidencias/WB041-retest.md`).

## Itens não-testáveis (com justificativa)

| ID | Justificativa |
|---|---|
| **WB070** | Exigiria derrubar `sgsm-ia` (`:8082`) temporariamente. O processo roda via IntelliJ IDEA (run configuration da IDE do usuário), sem comando de start que eu pudesse reproduzir com certeza para religar exatamente o mesmo processo depois. Seguindo a instrução explícita da tarefa, optei por não arriscar quebrar o ambiente de dev do usuário. |
| **WB071** | Mesma justificativa de WB070, para `ms-sboot-auth` (`:8081`). |
| **WB090** | Não há log em arquivo configurado (`logging.file.name` ausente em todos os `application*.yaml`); o processo roda em console externo gerenciado pela IDE, fora do alcance das ferramentas de terminal disponíveis para este subagente. Prova indireta (efeito no Redis/sistemas dependentes) foi usada em todos os outros itens como substituto. |
| **WB091** | Tentativas de boa-fé (remoteJid com número vazio, JSON profundamente aninhado) não reproduziram uma exceção não tratada dentro do bloco síncrono do controller — o código é bem protegido contra os edge cases plausíveis via HTTP externo (todos os campos são `String`/`Boolean` wrapper, checados null antes de uso). |

---

## Destaques (achados mais importantes da rodada, independente de "passar"/"falhar")

### WB050/WB051 — Achado de segurança: webhook sem validação de assinatura

**Confirmado.** `bot.evolution.webhook-secret` está configurado em `application-dev.yaml`, mas `WebhookController.receberEvento()` nunca lê nem compara nenhum header contra esse segredo — `BotProperties props` é injetado mas nunca referenciado no método. Testado ao vivo: uma requisição sem nenhum header de autenticação, e outra com um header `X-Evolution-Signature: qualquercoisa` completamente arbitrário, tiveram exatamente o mesmo resultado (200, processamento normal, efeito real no Redis). `POST /webhook/evolution` é `permitAll()` no `SecurityConfig` e aceita qualquer requisição bem formada de qualquer origem — sem forma alguma de verificar que a chamada realmente veio da Evolution API. Isso permite forjar mensagens de WhatsApp em nome de qualquer número, disparando fluxos reais (cadastro, agendamento, cancelamento) contra `sgsm`/`ms-sboot-auth`. Ver `evidencias/WB050.md` e `evidencias/WB051.md`.

### WB061 — Achado de concorrência: lost update confirmado na sessão Redis

**Confirmado e reproduzido.** Duas requisições HTTP disparadas em paralelo (`curl ... &`, quase simultâneas) para o mesmo número, com textos diferentes, resultaram na **perda completa** de uma das duas mensagens no histórico da sessão — a mensagem "A" desapareceu inteiramente (nem o turno do usuário nem a resposta do assistente aparecem em nenhum lugar do estado final), apesar de ambas as requisições HTTP terem retornado 200 normalmente, sem qualquer sinalização de erro. Causa raiz confirmada em `SessaoService.java`: `obter()`/`salvar()` fazem um simples `GET`+`SET` no Redis, sem `WATCH`/`MULTI` (optimistic locking), sem script Lua atômico e sem lock distribuído — um clássico read-modify-write sem isolamento. Como controle, o mesmo teste rodado de forma sequencial (WB060, aguardando a primeira chamada terminar antes de disparar a segunda) preservou corretamente as duas mensagens, isolando a concorrência como causa exclusiva da perda. Em produção, isso significa que mensagens legítimas do usuário podem simplesmente sumir da conversa sem nenhum erro visível, especialmente problemático se envolverem estados como `confirmacaoPendente`. Ver `evidencias/WB061.md`.

---

## Achados adicionais registrados durante a execução (não pedidos explicitamente no plano, descobertos ao investigar causas raízes)

- O mesmo mascaramento de PII que quebra WB030 também corrompeu um `agendamentoId` fictício no teste de WB042 (o RAG aplicou o padrão de máscara de CPF a um trecho de UUID) — sugere que é um heurístico genérico de "sequência numérica parece PII", não específico de CPF de cadastro.
- `SgsmClient.cancelarAgendamento()` não envia `Content-Type`/corpo no `PATCH`, fazendo o `sgsm` responder `400 Failed to read request` mesmo para um `agendamentoId` real e existente (confirmado por reprodução direta, comparando com e sem corpo) — acha mais amplo que o escopo de WB042.
- O campo `historico` salvo no Redis sempre reflete `resp.getRespostaUsuario()` (a sugestão do RAG), nunca o `respostaFinal` de fato computado/enviado pelo switch de intents em `BotOrquestradorService.processar()` — isso limita a validade do histórico como prova de "o que foi realmente enviado ao usuário" em vários itens (documentado item a item onde relevante).
