# WB030-retest2 — CADASTRAR completo até confirmacaoPendente.tipo="OTP" (re-teste após correção da branch CONFIRMAR)

Contexto: `ms-whatsapp-bot` foi recompilado/reiniciado com a correção em `BotOrquestradorService.processar()` (case `CONFIRMAR`): antes, a linha `sessao.setConfirmacaoPendente(null)` era executada incondicionalmente após `executarConfirmacao()`, sobrescrevendo o `"OTP"` que `executarCadastro()` acabara de setar. Agora só zera se o tipo não tiver mudado, preservando a transição para `"OTP"` no caso de sucesso do cadastro.

Este re-teste (segunda rodada, não sobrescreve `WB030-retest.md`, que documenta o achado do bug agora corrigido) repete a sequência de WB030 com número **novo**, conferido via grep em `evidencias/*.md` antes de iniciar (nenhuma ocorrência de `5511900000033`).

Número usado: `5511900000033`.
Dados de teste: nome "Carla Retest QA Fase4", CPF `111.444.777-35` (formato válido, dígitos verificadores calculados manualmente, fictício, nunca usado em outra evidência), nascimento `15/03/1990`, e-mail `qa.wb030.fase4.5511900000033@teste.com`.

## Passo 1 — primeiro contato
```bash
curl -s -X POST http://localhost:8083/webhook/evolution -H "Content-Type: application/json" \
  -d '{"event":"messages.upsert","instance":"sgsm-bot","data":{"key":{"remoteJid":"5511900000033@s.whatsapp.net","fromMe":false,"id":"WB030F4-1"},"pushName":"Carla QA Fase4","message":{"conversation":"oi"},"messageTimestamp":"1700002040"}}'
```
Status: 200.

Redis antes: `(nil)` (número nunca usado). Redis depois:
```json
{"numero":"5511900000033","usuarioId":null,"perfil":null,"accessToken":null,"refreshToken":null,"nomeUsuario":"Carla QA Fase4","consentimentoAceito":false,"historico":[],"confirmacaoPendente":null,"dadosCadastro":{}}
```

## Passo 2 — ACEITO
```bash
curl -s -X POST http://localhost:8083/webhook/evolution -H "Content-Type: application/json" \
  -d '{"event":"messages.upsert","instance":"sgsm-bot","data":{"key":{"remoteJid":"5511900000033@s.whatsapp.net","fromMe":false,"id":"WB030F4-2"},"pushName":"Carla QA Fase4","message":{"conversation":"ACEITO"},"messageTimestamp":"1700002041"}}'
```
Status: 200. Redis: `consentimentoAceito:true`.

## Passo 3 — intenção de cadastro
```bash
curl -s -X POST http://localhost:8083/webhook/evolution -H "Content-Type: application/json" \
  -d '{"event":"messages.upsert","instance":"sgsm-bot","data":{"key":{"remoteJid":"5511900000033@s.whatsapp.net","fromMe":false,"id":"WB030F4-3"},"pushName":"Carla QA Fase4","message":{"conversation":"quero me cadastrar"},"messageTimestamp":"1700002042"}}'
```
Status: 200. Redis (após ~3s, processamento assíncrono em virtual thread):
```json
{"historico":[{"papel":"USUARIO","texto":"quero me cadastrar"},{"papel":"ASSISTENTE","texto":"*Vamos iniciar seu cadastro!* Por favor, informe seu nome completo, e‑mail, CPF e data de nascimento."}]}
```

## Passo 4 — fornece todos os dados em uma mensagem
```bash
curl -s -X POST http://localhost:8083/webhook/evolution -H "Content-Type: application/json" \
  -d '{"event":"messages.upsert","instance":"sgsm-bot","data":{"key":{"remoteJid":"5511900000033@s.whatsapp.net","fromMe":false,"id":"WB030F4-4"},"pushName":"Carla QA Fase4","message":{"conversation":"Meu nome completo e Carla Retest QA Fase4, meu CPF e 111.444.777-35, nasci em 15/03/1990 e meu email e qa.wb030.fase4.5511900000033@teste.com"},"messageTimestamp":"1700002043"}}'
```
Status: 200.

Redis depois (3s):
```json
{"numero":"5511900000033","usuarioId":null,"perfil":null,"accessToken":null,"refreshToken":null,"nomeUsuario":"Carla QA Fase4","consentimentoAceito":true,
"historico":[
  {"papel":"USUARIO","texto":"quero me cadastrar"},
  {"papel":"ASSISTENTE","texto":"*Vamos iniciar seu cadastro!* Por favor, informe seu nome completo, e‑mail, CPF e data de nascimento."},
  {"papel":"USUARIO","texto":"Meu nome completo e Carla Retest QA Fase4, meu CPF e 111.444.777-35, nasci em 15/03/1990 e meu email e qa.wb030.fase4.5511900000033@teste.com"},
  {"papel":"ASSISTENTE","texto":"*Cadastro recebido!* Nome: Carla Retest QA Fase4, CPF: ***.***.***-**, Nascimento: 15/03/1990, e‑mail: qa.wb030.fase4.***.***.***-**33@teste.com. Por favor, confirme para concluirmos."}
],
"confirmacaoPendente":{"tipo":"CADASTRAR","payload":{"cpf":"111.444.777-35","dataNascimento":"1990-03-15","email":"qa.wb030.fase4.5511900000033@teste.com","nomeCompleto":"Carla Retest QA Fase4"}},
"dadosCadastro":{"nomeCompleto":"Carla Retest QA Fase4","cpf":"111.444.777-35","dataNascimento":"1990-03-15","email":"qa.wb030.fase4.5511900000033@teste.com"}}
```
CPF e e-mail chegam **intactos** em `confirmacaoPendente.payload`/`dadosCadastro` (mascaramento aplicado apenas ao texto de conversa em `historico[].ASSISTENTE`, comportamento já confirmado correto em `WB030-retest.md` e não alterado nesta rodada).

## Passo 5 — "confirmo" (ponto central deste re-teste)
```bash
curl -s -X POST http://localhost:8083/webhook/evolution -H "Content-Type: application/json" \
  -d '{"event":"messages.upsert","instance":"sgsm-bot","data":{"key":{"remoteJid":"5511900000033@s.whatsapp.net","fromMe":false,"id":"WB030F4-5"},"pushName":"Carla QA Fase4","message":{"conversation":"confirmo"},"messageTimestamp":"1700002044"}}'
```
Status: 200.

Redis depois (~5s, chamadas reais a `sgsm`/`ms-sboot-auth`):
```json
{"historico":[..., {"papel":"USUARIO","texto":"confirmo"}, {"papel":"ASSISTENTE","texto":"*Cadastro concluído com sucesso!* Seja bem‑vinda, Carla Retest QA Fase4."}],
"confirmacaoPendente":{"tipo":"OTP","payload":{"email":"qa.wb030.fase4.5511900000033@teste.com"}},
"dadosCadastro":{"nomeCompleto":"Carla Retest QA Fase4","cpf":"111.444.777-35","dataNascimento":"1990-03-15","email":"qa.wb030.fase4.5511900000033@teste.com"}}
```

**CONFIRMADO: a correção da branch `CONFIRMAR` funcionou.** `confirmacaoPendente.tipo` chegou a `"OTP"` (não voltou a `null`), com `payload.email` igual ao e-mail cadastrado. Esta é a diferença direta em relação a `WB030-retest.md`, onde o mesmo passo produzia `confirmacaoPendente:null` diretamente, sem nunca passar por `"OTP"`.

## Verificação real nos sistemas dependentes

### 1. Conta criada no `ms-sboot-auth` — confirmado via geração de OTP real
```bash
curl -s -X POST http://localhost:8081/v1/api/auth/otp/gerar -H "Content-Type: application/json" \
  -d '{"email":"qa.wb030.fase4.5511900000033@teste.com"}'
```
Resposta: `200 {"otp":"815436","ttlSegundos":300}` — conta existe.

### 2. OTP verificado com sucesso (prova que a conta está ativa)
```bash
curl -s -X POST http://localhost:8081/v1/api/auth/otp/verificar -H "Content-Type: application/json" \
  -d '{"email":"qa.wb030.fase4.5511900000033@teste.com","otp":"815436"}'
```
Resposta (token redigido):
```json
{"accessToken":"[REDIGIDO]","expiresIn":900,"refreshToken":"[REDIGIDO]","tipo":"Bearer","tipoPerfil":"PACIENTE"}
```
Claims não sensíveis do JWT: `email:"qa.wb030.fase4.5511900000033@teste.com"`, `nome:"Carla Retest QA Fase4"`, `perfil:"PACIENTE"`, `referenciaId:"968b2b6d-0c81-423f-9689-426cc44dce3c"`, `roles:["PACIENTE"]`.

### 3. Paciente criado no `sgsm` — confirmado via GET real com o accessToken real
```bash
curl -s -H "Authorization: Bearer [REDIGIDO]" "http://localhost:8080/v1/api/pacientes/968b2b6d-0c81-423f-9689-426cc44dce3c"
```
Resposta: `200`
```json
{"id":"968b2b6d-0c81-423f-9689-426cc44dce3c","nome":"Carla Retest QA Fase4","cpf":"111.444.777-35","dataNascimento":"1990-03-15","email":"qa.wb030.fase4.5511900000033@teste.com","telefone":"5511900000033","logradouro":null,"numero":null,"complemento":null,"bairro":null,"cidade":null,"uf":null,"cep":null,"ativo":true,"criadoEm":"2026-08-25T17:26:06.780425Z","atualizadoEm":"2026-08-25T17:26:06.780425Z"}
```
Paciente real, com CPF/e-mail íntegros e iguais aos enviados.

Nota metodológica (herdada de WB030/WB030-retest, ainda válida): `historico[].ASSISTENTE` reflete `resp.getRespostaUsuario()` do RAG, não o `respostaFinal` real executado. A prova definitiva está no campo `confirmacaoPendente` e nos sistemas dependentes, acima.

## Resultado

**APROVADO.** `confirmacaoPendente.tipo` chegou a `"OTP"` no Redis (critério de aceite atendido, ao contrário de `WB030-retest.md`), com paciente real criado no `sgsm` (`id:968b2b6d-0c81-423f-9689-426cc44dce3c`) e conta real criada no `ms-sboot-auth`, ambos confirmados por chamadas reais (GET com token real, geração/verificação de OTP real). A sessão criada nesta evidência (`5511900000033`) foi reaproveitada em seguida para o re-teste de WB041 (ver `evidencias/WB041-retest.md`), continuando o mesmo fluxo contínuo com o OTP real (código `325091`, gerado após este teste) enviado pelo webhook.
