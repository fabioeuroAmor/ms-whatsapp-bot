# WB023-retest — pergunta sobre cancelar sem estar autenticado (re-teste após correção do `sgsm-ia`)

Contexto: mesma correção descrita em `evidencias/WB022-retest.md` — `SYSTEM_PROMPT` do `sgsm-ia` ganhou regra explícita para pedir e-mail antes de prosseguir com `AGENDAR`/`CANCELAR` quando o perfil não é identificado, mais um reforço determinístico no código que sobrescreve a resposta pedindo e-mail sempre que o intent classificado for `AGENDAR`/`CANCELAR` e `perfil == null`, independente do LLM.

Este re-teste repete o cenário original de WB023 (ver `evidencias/WB023.md`) com dois números novos, não usados em nenhuma evidência anterior (conferido via grep em `evidencias/*.md`: `5511900000124` e `5511900000125`), e duas frases diferentes de intenção de cancelamento.

## Tentativa 1 — número `5511900000124`, frase "quero cancelar minha consulta"

### Passo 1 — "oi"
```bash
curl -s -i -X POST http://localhost:8083/webhook/evolution -H "Content-Type: application/json" \
  -d '{"event":"messages.upsert","instance":"sgsm-bot","data":{"key":{"remoteJid":"5511900000124@s.whatsapp.net","fromMe":false,"id":"WB023R1-1"},"pushName":"QA Tester 3","message":{"conversation":"oi"},"messageTimestamp":"1700002201"}}'
```
Status: `200`.

### Passo 2 — "ACEITO"
```bash
curl -s -i -X POST http://localhost:8083/webhook/evolution -H "Content-Type: application/json" \
  -d '{"event":"messages.upsert","instance":"sgsm-bot","data":{"key":{"remoteJid":"5511900000124@s.whatsapp.net","fromMe":false,"id":"WB023R1-2"},"pushName":"QA Tester 3","message":{"conversation":"ACEITO"},"messageTimestamp":"1700002202"}}'
```
Status: `200`. Redis depois:
```json
{"numero":"5511900000124","usuarioId":null,"perfil":null,"accessToken":null,"refreshToken":null,"nomeUsuario":"QA Tester 3","consentimentoAceito":true,"historico":[],"confirmacaoPendente":null,"dadosCadastro":{}}
```

### Passo 3 — "quero cancelar minha consulta" (mensagem de teste do item)
```bash
curl -s -i -X POST http://localhost:8083/webhook/evolution -H "Content-Type: application/json" \
  -d '{"event":"messages.upsert","instance":"sgsm-bot","data":{"key":{"remoteJid":"5511900000124@s.whatsapp.net","fromMe":false,"id":"WB023R1-3"},"pushName":"QA Tester 3","message":{"conversation":"quero cancelar minha consulta"},"messageTimestamp":"1700002203"}}'
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
Date: Tue, 25 Aug 2026 17:25:54 GMT
```
Redis depois (~4s):
```json
{"numero":"5511900000124","usuarioId":null,"perfil":null,"accessToken":null,"refreshToken":null,"nomeUsuario":"QA Tester 3","consentimentoAceito":true,"historico":[{"papel":"USUARIO","texto":"quero cancelar minha consulta"},{"papel":"ASSISTENTE","texto":"Para cancelar, primeiro preciso confirmar sua identidade. Digite seu e-mail cadastrado."}],"confirmacaoPendente":null,"dadosCadastro":{}}
```

**Resultado tentativa 1: PASSOU** — a resposta agora pede explicitamente o e-mail cadastrado ("Para cancelar, primeiro preciso confirmar sua identidade. Digite seu e-mail cadastrado."). `accessToken` continua `null`. `confirmacaoPendente` continua `null`.

## Tentativa 2 — número `5511900000125`, frase "preciso cancelar meu horario marcado"

### Passo 1 — "oi"
```bash
curl -s -i -X POST http://localhost:8083/webhook/evolution -H "Content-Type: application/json" \
  -d '{"event":"messages.upsert","instance":"sgsm-bot","data":{"key":{"remoteJid":"5511900000125@s.whatsapp.net","fromMe":false,"id":"WB023R2-1"},"pushName":"QA Tester 4","message":{"conversation":"oi"},"messageTimestamp":"1700002301"}}'
```
Status: `200`.

### Passo 2 — "ACEITO"
```bash
curl -s -i -X POST http://localhost:8083/webhook/evolution -H "Content-Type: application/json" \
  -d '{"event":"messages.upsert","instance":"sgsm-bot","data":{"key":{"remoteJid":"5511900000125@s.whatsapp.net","fromMe":false,"id":"WB023R2-2"},"pushName":"QA Tester 4","message":{"conversation":"ACEITO"},"messageTimestamp":"1700002302"}}'
```
Status: `200`. Redis depois:
```json
{"numero":"5511900000125","usuarioId":null,"perfil":null,"accessToken":null,"refreshToken":null,"nomeUsuario":"QA Tester 4","consentimentoAceito":true,"historico":[],"confirmacaoPendente":null,"dadosCadastro":{}}
```

### Passo 3 — "preciso cancelar meu horário marcado" (frase diferente da tentativa 1, para checar consistência do LLM)

Primeira tentativa desta requisição, com o texto acentuado original `"preciso cancelar meu horário marcado"`, retornou `400 Bad Request` / `{"detail":"Failed to read request",...}`. Essa é a mesma limitação de encoding do shell (Git Bash no Windows corrompendo bytes UTF-8 ao montar `-d` inline) já documentada em `evidencias/WB013.md` — não é um bug do serviço. Reexecutado com texto ASCII-safe equivalente, seguindo a mesma mitigação já usada em WB013/WB086:

```bash
curl -s -i -X POST http://localhost:8083/webhook/evolution -H "Content-Type: application/json" \
  -d '{"event":"messages.upsert","instance":"sgsm-bot","data":{"key":{"remoteJid":"5511900000125@s.whatsapp.net","fromMe":false,"id":"WB023R2-3"},"pushName":"QA Tester 4","message":{"conversation":"preciso cancelar meu horario marcado"},"messageTimestamp":"1700002303"}}'
```
Status: `200`. Redis depois (~4s):
```json
{"numero":"5511900000125","usuarioId":null,"perfil":null,"accessToken":null,"refreshToken":null,"nomeUsuario":"QA Tester 4","consentimentoAceito":true,"historico":[{"papel":"USUARIO","texto":"preciso cancelar meu horario marcado"},{"papel":"ASSISTENTE","texto":"Para cancelar, primeiro preciso confirmar sua identidade. Digite seu e-mail cadastrado."}],"confirmacaoPendente":null,"dadosCadastro":{}}
```

**Resultado tentativa 2: PASSOU** — mesma resposta determinística de pedido de e-mail, com frase diferente e sem menção literal a "consulta"/"cancelar minha consulta". `accessToken` e `confirmacaoPendente` continuam `null`.

## Resultado final

**APROVADO** — as duas tentativas, com frases diferentes ("quero cancelar minha consulta" e "preciso cancelar meu horario marcado") e sessões novas, produziram a mesma resposta determinística pedindo o e-mail cadastrado antes de prosseguir com o cancelamento. Os invariantes de estado (`accessToken: null`, `confirmacaoPendente: null`) se mantiveram em ambas as tentativas.

Item WB023 muda de REPROVADO para **APROVADO**.
