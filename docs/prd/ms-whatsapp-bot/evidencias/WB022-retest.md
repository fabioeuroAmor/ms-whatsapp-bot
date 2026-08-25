# WB022-retest — pergunta sobre agendar sem estar autenticado (re-teste após correção do `sgsm-ia`)

Contexto: `sgsm-ia` foi recompilado/reiniciado com duas mudanças em `WhatsAppService.java`: (1) `SYSTEM_PROMPT` ganhou regra explícita para não trocar a intenção para `AUTENTICAR` só por causa do perfil não identificado, e para pedir e-mail antes de prosseguir com `AGENDAR`/`CANCELAR` quando o perfil vier nulo; (2) reforço determinístico no código — se o intent classificado for `AGENDAR`/`CANCELAR` e o perfil vier nulo, a resposta é sobrescrita determinísticamente pedindo e-mail, independente do que o LLM tenha respondido.

Este re-teste repete o cenário original de WB022 (ver `evidencias/WB022.md`) com dois números novos, não usados em nenhuma evidência anterior (conferido via grep em `evidencias/*.md` antes de iniciar: `5511900000122` e `5511900000123`), e duas frases diferentes de intenção de agendamento, para checar consistência do LLM (o achado original apontava comportamento não-determinístico).

## Tentativa 1 — número `5511900000122`, frase "quero marcar uma consulta"

### Passo 1 — primeiro contato ("oi")
```bash
curl -s -i -X POST http://localhost:8083/webhook/evolution -H "Content-Type: application/json" \
  -d '{"event":"messages.upsert","instance":"sgsm-bot","data":{"key":{"remoteJid":"5511900000122@s.whatsapp.net","fromMe":false,"id":"WB022R1-1"},"pushName":"QA Tester","message":{"conversation":"oi"},"messageTimestamp":"1700002001"}}'
```
Status: `200`. Redis antes: `(nil)`. Redis depois:
```json
{"numero":"5511900000122","usuarioId":null,"perfil":null,"accessToken":null,"refreshToken":null,"nomeUsuario":"QA Tester","consentimentoAceito":false,"historico":[],"confirmacaoPendente":null,"dadosCadastro":{}}
```

### Passo 2 — "ACEITO"
```bash
curl -s -i -X POST http://localhost:8083/webhook/evolution -H "Content-Type: application/json" \
  -d '{"event":"messages.upsert","instance":"sgsm-bot","data":{"key":{"remoteJid":"5511900000122@s.whatsapp.net","fromMe":false,"id":"WB022R1-2"},"pushName":"QA Tester","message":{"conversation":"ACEITO"},"messageTimestamp":"1700002002"}}'
```
Status: `200`. Redis depois: `consentimentoAceito:true` (demais campos inalterados).

### Passo 3 — "quero marcar uma consulta" (mensagem de teste do item)
```bash
curl -s -i -X POST http://localhost:8083/webhook/evolution -H "Content-Type: application/json" \
  -d '{"event":"messages.upsert","instance":"sgsm-bot","data":{"key":{"remoteJid":"5511900000122@s.whatsapp.net","fromMe":false,"id":"WB022R1-3"},"pushName":"QA Tester","message":{"conversation":"quero marcar uma consulta"},"messageTimestamp":"1700002003"}}'
```
Resposta crua:
```
HTTP/1.1 200
X-Content-Type-Options: nosniff
X-XSS-Protection: 0
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Expires: 0
X-Frame-Options: DENY
Content-Length: 0
Date: Tue, 25 Aug 2026 17:24:51 GMT
```
Redis depois (~3s de processamento assíncrono):
```json
{"numero":"5511900000122","usuarioId":null,"perfil":null,"accessToken":null,"refreshToken":null,"nomeUsuario":"QA Tester","consentimentoAceito":true,"historico":[{"papel":"USUARIO","texto":"quero marcar uma consulta"},{"papel":"ASSISTENTE","texto":"Para agendar, primeiro preciso confirmar sua identidade. Digite seu e-mail cadastrado."}],"confirmacaoPendente":null,"dadosCadastro":{}}
```

**Resultado tentativa 1: PASSOU** — a resposta agora pede explicitamente o e-mail cadastrado ("Para agendar, primeiro preciso confirmar sua identidade. Digite seu e-mail cadastrado."), texto idêntico ao que a análise de causa raiz do WB022 original apontava como o texto determinístico esperado em `BotOrquestradorService`. `accessToken` continua `null`. `confirmacaoPendente` continua `null`.

## Tentativa 2 — número `5511900000123`, frase "preciso agendar com um cardiologista"

### Passo 1 — "oi"
```bash
curl -s -i -X POST http://localhost:8083/webhook/evolution -H "Content-Type: application/json" \
  -d '{"event":"messages.upsert","instance":"sgsm-bot","data":{"key":{"remoteJid":"5511900000123@s.whatsapp.net","fromMe":false,"id":"WB022R2-1"},"pushName":"QA Tester 2","message":{"conversation":"oi"},"messageTimestamp":"1700002101"}}'
```
Status: `200`.

### Passo 2 — "ACEITO"
```bash
curl -s -i -X POST http://localhost:8083/webhook/evolution -H "Content-Type: application/json" \
  -d '{"event":"messages.upsert","instance":"sgsm-bot","data":{"key":{"remoteJid":"5511900000123@s.whatsapp.net","fromMe":false,"id":"WB022R2-2"},"pushName":"QA Tester 2","message":{"conversation":"ACEITO"},"messageTimestamp":"1700002102"}}'
```
Status: `200`. Redis depois:
```json
{"numero":"5511900000123","usuarioId":null,"perfil":null,"accessToken":null,"refreshToken":null,"nomeUsuario":"QA Tester 2","consentimentoAceito":true,"historico":[],"confirmacaoPendente":null,"dadosCadastro":{}}
```

### Passo 3 — "preciso agendar com um cardiologista" (frase diferente da tentativa 1, para checar consistência do LLM)
```bash
curl -s -i -X POST http://localhost:8083/webhook/evolution -H "Content-Type: application/json" \
  -d '{"event":"messages.upsert","instance":"sgsm-bot","data":{"key":{"remoteJid":"5511900000123@s.whatsapp.net","fromMe":false,"id":"WB022R2-3"},"pushName":"QA Tester 2","message":{"conversation":"preciso agendar com um cardiologista"},"messageTimestamp":"1700002103"}}'
```
Status: `200`. Redis depois (~7s — processamento assíncrono levou mais tempo do que na tentativa 1 nesta execução):
```json
{"numero":"5511900000123","usuarioId":null,"perfil":null,"accessToken":null,"refreshToken":null,"nomeUsuario":"QA Tester 2","consentimentoAceito":true,"historico":[{"papel":"USUARIO","texto":"preciso agendar com um cardiologista"},{"papel":"ASSISTENTE","texto":"Para agendar, primeiro preciso confirmar sua identidade. Digite seu e-mail cadastrado."}],"confirmacaoPendente":null,"dadosCadastro":{}}
```

**Resultado tentativa 2: PASSOU** — mesma resposta determinística de pedido de e-mail, mesmo com frase diferente e sem menção literal a "consulta". `accessToken` e `confirmacaoPendente` continuam `null`.

## Resultado final

**APROVADO** — as duas tentativas, com frases diferentes ("quero marcar uma consulta" e "preciso agendar com um cardiologista") e sessões novas, produziram exatamente a mesma resposta determinística pedindo o e-mail cadastrado antes de prosseguir com o agendamento, consistente com o reforço determinístico descrito no contexto da correção (o texto não varia por depender do LLM, já que é sobrescrito no código quando `perfil == null` e intent é `AGENDAR`). Os invariantes de estado (`accessToken: null`, `confirmacaoPendente: null`) se mantiveram em ambas as tentativas, como já ocorria no teste original.

Item WB022 muda de REPROVADO para **APROVADO**.
