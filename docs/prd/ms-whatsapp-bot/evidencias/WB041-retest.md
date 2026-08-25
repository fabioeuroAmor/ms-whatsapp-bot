# WB041-retest — usuarioId preenchido via OTP + AGENDAR completo até agendamento real criado

Contexto: duas causas corrigidas em `ms-whatsapp-bot` desde a reprovação original (`evidencias/WB041.md`):
1. `SessaoBot.usuarioId` nunca era atribuído após autenticação via OTP. Correção: `processarOtp()` agora chama `GET /v1/api/auth/me` com o `accessToken` recém-obtido e seta `sessao.usuarioId` a partir de `referenciaId`.
2. `ExecutorAcaoService.combinarDataHora()` só concatenava `data + "T" + hora` sem normalizar formato. Correção: agora aceita `dd/MM/yyyy` e `yyyy-MM-dd`, montando um `OffsetDateTime` ISO completo com fuso `America/Sao_Paulo`.

Este re-teste continua o **mesmo fluxo contínuo** de `WB030-retest2.md` (mesma sessão `5511900000033`, cadastro real já concluído, `confirmacaoPendente.tipo` chegou a `"OTP"` no teste anterior), com o objetivo de validar as duas correções acima até um agendamento real criado no `sgsm`.

Dados reais reaproveitados de `evidencias/WB041.md` (mesmo ambiente, IDs ainda válidos, confirmados abaixo pela criação bem-sucedida do agendamento):
- `medicoId`: `ff9a2400-2217-48fa-ac91-b81a4032f2db` (Fabio Monteiro Amorim)
- `servicoMedicoId`: `241760a1-efdc-44c6-b3c2-1f3a64013fe4` (Consulta de Rotina)

## Parte 1 — gerar OTP real e enviar pelo webhook (valida usuarioId)

```bash
curl -s -X POST http://localhost:8081/v1/api/auth/otp/gerar -H "Content-Type: application/json" \
  -d '{"email":"qa.wb030.fase4.5511900000033@teste.com"}'
```
Resposta: `200 {"otp":"325091","ttlSegundos":300}`.

```bash
curl -s -X POST http://localhost:8083/webhook/evolution -H "Content-Type: application/json" \
  -d '{"event":"messages.upsert","instance":"sgsm-bot","data":{"key":{"remoteJid":"5511900000033@s.whatsapp.net","fromMe":false,"id":"WB030F4-6"},"pushName":"Carla QA Fase4","message":{"conversation":"325091"},"messageTimestamp":"1700002045"}}'
```
Status: 200.

Redis depois (~4s):
```json
{"numero":"5511900000033","usuarioId":"968b2b6d-0c81-423f-9689-426cc44dce3c","perfil":"PACIENTE",
"accessToken":"[REDIGIDO]","refreshToken":"[REDIGIDO]",
"nomeUsuario":"Carla QA Fase4","consentimentoAceito":true,
"confirmacaoPendente":null,
"dadosCadastro":{"nomeCompleto":"Carla Retest QA Fase4","cpf":"111.444.777-35","dataNascimento":"1990-03-15","email":"qa.wb030.fase4.5511900000033@teste.com"}}
```

**CONFIRMADO — ACHADO 1 de `WB041.md` corrigido**: `usuarioId` chegou preenchido com `"968b2b6d-0c81-423f-9689-426cc44dce3c"` (não `null`). Este é exatamente o mesmo UUID de `referenciaId` retornado no JWT do OTP (verificado em `WB030-retest2.md`) e o mesmo `id` do paciente real no `sgsm`.

Confirmação independente via `GET /v1/api/auth/me` com o `accessToken` real da sessão:
```bash
curl -s -H "Authorization: Bearer [REDIGIDO]" "http://localhost:8081/v1/api/auth/me"
```
Resposta:
```json
{"id":"c69ca1a6-a053-4b0d-b2e7-2b874dafad35","email":"qa.wb030.fase4.5511900000033@teste.com","nome":"Carla Retest QA Fase4","perfil":"PACIENTE","referenciaId":"968b2b6d-0c81-423f-9689-426cc44dce3c","roles":["PACIENTE"],"permissions":["agendamento:read","agendamento:cancel","agendamento:write","servico:read"]}
```
`referenciaId` bate exatamente com o `usuarioId` gravado na sessão — confirma que `processarOtp()` chamou `/v1/api/auth/me` de verdade e usou o campo correto.

## Parte 2 — fluxo AGENDAR completo, data em dd/MM/yyyy (valida combinarDataHora)

Turno 1 — pedido inicial (RAG ainda não resolve nome de médico/serviço para IDs, mesmo padrão observado em `WB041.md`):
```bash
curl -s -X POST http://localhost:8083/webhook/evolution -H "Content-Type: application/json" \
  -d '{"event":"messages.upsert","instance":"sgsm-bot","data":{"key":{"remoteJid":"5511900000033@s.whatsapp.net","fromMe":false,"id":"WB041F4-1"},"pushName":"Carla QA Fase4","message":{"conversation":"Quero agendar uma Consulta de Rotina com o medico Fabio Monteiro Amorim no dia 10/09/2026 as 14:00"},"messageTimestamp":"1700002046"}}'
```
Resposta (RAG pede nome/e-mail/confirmação de especialidade): "*Para concluir o agendamento, preciso do seu nome completo, e‑mail e confirmar a especialidade (Neurologia).*"

Turno 2 — fornece nome/e-mail:
```bash
curl -s -X POST http://localhost:8083/webhook/evolution -H "Content-Type: application/json" \
  -d '{"event":"messages.upsert","instance":"sgsm-bot","data":{"key":{"remoteJid":"5511900000033@s.whatsapp.net","fromMe":false,"id":"WB041F4-2"},"pushName":"Carla QA Fase4","message":{"conversation":"Meu nome completo e Carla Retest QA Fase4, meu email e qa.wb030.fase4.5511900000033@teste.com"},"messageTimestamp":"1700002047"}}'
```
Resposta: "*Por favor, confirme a especialidade (Neurologia) para concluir o agendamento.*"

Turno 3 — confirma especialidade e fornece IDs reais (mesmo padrão de `WB041.md`: o RAG não resolve nome→UUID sozinho, então os IDs são fornecidos explicitamente como o QA já fazia antes):
```bash
curl -s -X POST http://localhost:8083/webhook/evolution -H "Content-Type: application/json" \
  -d '{"event":"messages.upsert","instance":"sgsm-bot","data":{"key":{"remoteJid":"5511900000033@s.whatsapp.net","fromMe":false,"id":"WB041F4-3"},"pushName":"Carla QA Fase4","message":{"conversation":"Sim, confirmo a especialidade Neurologia. medicoId ff9a2400-2217-48fa-ac91-b81a4032f2db e servicoMedicoId 241760a1-efdc-44c6-b3c2-1f3a64013fe4"},"messageTimestamp":"1700002048"}}'
```
Status: 200. Redis depois (`confirmacaoPendente` finalmente populado):
```json
"confirmacaoPendente":{"tipo":"AGENDAR","payload":{"medicoId":"ff9a2400-2217-48fa-ac91-b81a4032f2db","servicoMedicoId":"241760a1-efdc-44c6-b3c2-1f3a64013fe4","data":"10/09/2026","hora":"14:00","pacienteId":"968b2b6d-0c81-423f-9689-426cc44dce3c"}}
```

**CONFIRMADO — ACHADO 1 de `WB041.md` também refletido aqui**: `pacienteId` agora vem preenchido (`"968b2b6d-0c81-423f-9689-426cc44dce3c"`, igual ao `usuarioId` da sessão), ao contrário do `null` observado em `WB041.md` no mesmo ponto do fluxo. `data` continua no formato `"10/09/2026"` (dd/MM/yyyy, como o usuário digitou) — isso é esperado: a normalização acontece em `ExecutorAcaoService.combinarDataHora()`, no momento de montar a chamada ao `sgsm`, não na extração de entidades do RAG.

## Confirmação ("confirmo")
```bash
curl -s -X POST http://localhost:8083/webhook/evolution -H "Content-Type: application/json" \
  -d '{"event":"messages.upsert","instance":"sgsm-bot","data":{"key":{"remoteJid":"5511900000033@s.whatsapp.net","fromMe":false,"id":"WB041F4-4"},"pushName":"Carla QA Fase4","message":{"conversation":"confirmo"},"messageTimestamp":"1700002049"}}'
```
Status: 200. Redis depois (~5s): `confirmacaoPendente:null`; histórico mostra "*Confirmação recebida!* Seu agendamento será finalizado em instantes." (nota metodológica de sempre: `historico` reflete `resp.getRespostaUsuario()`, não prova real — a prova está abaixo).

## Verificação real no sgsm (prova definitiva)

```bash
curl -s -H "Authorization: Bearer [REDIGIDO]" "http://localhost:8080/v1/api/agendamentos"
```
Resposta: `200`
```json
[{"id":"7309b42d-d11c-42d4-baa9-9901598be54a","pacienteId":"968b2b6d-0c81-423f-9689-426cc44dce3c","pacienteNome":"Carla Retest QA Fase4","servicoMedicoId":"241760a1-efdc-44c6-b3c2-1f3a64013fe4","servicoMedicoNome":"Consulta de Rotina","medicoId":"ff9a2400-2217-48fa-ac91-b81a4032f2db","medicoNome":"Fabio Monteiro Amorim","tipo":"PRESENCIAL","dataHoraInicio":"2026-09-10T17:00:00Z","dataHoraFim":"2026-09-10T17:30:00Z","status":"PENDENTE","criadoEm":"2026-08-25T17:29:23.735343Z","atualizadoEm":"2026-08-25T17:29:23.735343Z"}]
```

**Agendamento real criado, confirmando as duas correções:**
- `pacienteId`: `"968b2b6d-0c81-423f-9689-426cc44dce3c"` — idêntico ao `usuarioId`/`referenciaId` da sessão (não `null` como em `WB041.md`).
- `dataHoraInicio`: `"2026-09-10T17:00:00Z"` — ISO válido com offset. O usuário digitou `10/09/2026` às `14:00` (horário local `America/Sao_Paulo`, UTC-3); `14:00 -03:00` equivale exatamente a `17:00Z`, confirmando que `combinarDataHora()` converteu `dd/MM/yyyy` + hora local para `OffsetDateTime` ISO com o fuso correto antes de enviar ao `sgsm` (o `sgsm`/Postgres armazenou e retornou em UTC).
- `medicoId`/`servicoMedicoId`/`medicoNome`/`servicoMedicoNome` batem exatamente com os dados reais fornecidos.
- `status:"PENDENTE"` — agendamento genuíno, recém-criado (`criadoEm` no mesmo instante do teste).

## Resultado

**APROVADO.** Ambas as causas raízes de `WB041.md` foram corrigidas e confirmadas por reprodução completa do fluxo real, autenticado, ponta a ponta:
1. `usuarioId` é atribuído corretamente após OTP (`processarOtp()` chama `/v1/api/auth/me` e usa `referenciaId`), refletido em `pacienteId` correto no payload de agendamento e no agendamento real criado.
2. `combinarDataHora()` normaliza `dd/MM/yyyy` (como um usuário real digitaria) para `OffsetDateTime` ISO com fuso `America/Sao_Paulo`, aceito sem erro pelo `sgsm` (`dataHoraInicio` válido, sem mais o `400 Bad Request` de "Failed to read request" observado em `WB041.md`).

Agendamento real confirmado: `id:7309b42d-d11c-42d4-baa9-9901598be54a`, `pacienteId:968b2b6d-0c81-423f-9689-426cc44dce3c`, `dataHoraInicio:2026-09-10T17:00:00Z`, `status:PENDENTE`. `confirmacaoPendente` voltou a `null` após a confirmação, como esperado.
