# WB030-retest — CADASTRAR com dados completos e confirmação (re-teste após correção de mascaramento de PII)

Contexto: `sgsm-ia` foi recompilado/reiniciado com a correção em `WhatsAppService.java` que inverteu a ordem parse→guardrail, para que `SanitizacaoGuardrail` só mascare `respostaUsuario` (texto de conversa), nunca `entidades.cpf`/`entidades.email`. Este re-teste repete a sequência de WB030 original com número NOVO (não usado em nenhuma evidência anterior — conferido via grep em `evidencias/*.md` antes de iniciar).

Número usado: `5511900000031`.
Dados de teste: nome "Beatriz Retest QA", CPF `987.654.321-00` (formato válido, dígitos verificadores calculados manualmente, fictício, nunca usado em outra evidência), nascimento `20/07/1988`, e-mail `qa.wb030retest.5511900000031@teste.com`.

## Passo 1 — primeiro contato
```bash
curl -s -X POST http://localhost:8083/webhook/evolution -H "Content-Type: application/json" \
  -d '{"event":"messages.upsert","instance":"sgsm-bot","data":{"key":{"remoteJid":"5511900000031@s.whatsapp.net","fromMe":false,"id":"WB030R-1"},"pushName":"Beatriz QA Retest","message":{"conversation":"oi"},"messageTimestamp":"1700001040"}}'
```
Status: 200. Redis: sessão criada, `consentimentoAceito:false`.

## Passo 2 — ACEITO
```bash
curl -s -X POST http://localhost:8083/webhook/evolution -H "Content-Type: application/json" \
  -d '{"event":"messages.upsert","instance":"sgsm-bot","data":{"key":{"remoteJid":"5511900000031@s.whatsapp.net","fromMe":false,"id":"WB030R-2"},"pushName":"Beatriz QA Retest","message":{"conversation":"ACEITO"},"messageTimestamp":"1700001041"}}'
```
Status: 200. Redis: `consentimentoAceito:true`.

## Passo 3 — intenção de cadastro
```bash
curl -s -X POST http://localhost:8083/webhook/evolution -H "Content-Type: application/json" \
  -d '{"event":"messages.upsert","instance":"sgsm-bot","data":{"key":{"remoteJid":"5511900000031@s.whatsapp.net","fromMe":false,"id":"WB030R-3"},"pushName":"Beatriz QA Retest","message":{"conversation":"quero me cadastrar"},"messageTimestamp":"1700001042"}}'
```
Status: 200. Redis (após ~2-3s de processamento assíncrono em virtual thread):
```json
{"historico":[{"papel":"USUARIO","texto":"quero me cadastrar"},{"papel":"ASSISTENTE","texto":"*Claro! Para concluir seu cadastro, preciso das seguintes informações:*"}]}
```

## Passo 4 — fornece todos os dados em uma mensagem
```bash
curl -s -X POST http://localhost:8083/webhook/evolution -H "Content-Type: application/json" \
  -d '{"event":"messages.upsert","instance":"sgsm-bot","data":{"key":{"remoteJid":"5511900000031@s.whatsapp.net","fromMe":false,"id":"WB030R-4"},"pushName":"Beatriz QA Retest","message":{"conversation":"Meu nome completo e Beatriz Retest QA, meu CPF e 987.654.321-00, nasci em 20/07/1988 e meu email e qa.wb030retest.5511900000031@teste.com"},"messageTimestamp":"1700001043"}}'
```
Status: 200.

Redis depois (3s):
```json
{"numero":"5511900000031","usuarioId":null,"perfil":null,"accessToken":null,"refreshToken":null,"nomeUsuario":"Beatriz QA Retest","consentimentoAceito":true,
"historico":[
  {"papel":"USUARIO","texto":"quero me cadastrar"},
  {"papel":"ASSISTENTE","texto":"*Claro! Para concluir seu cadastro, preciso das seguintes informações:*"},
  {"papel":"USUARIO","texto":"Meu nome completo e Beatriz Retest QA, meu CPF e 987.654.321-00, nasci em 20/07/1988 e meu email e qa.wb030retest.5511900000031@teste.com"},
  {"papel":"ASSISTENTE","texto":"*Perfeito, Beatriz Retest QA! Recebemos seu CPF, data de nascimento e email.*\n*Confirma o cadastro?*"}
],
"confirmacaoPendente":{"tipo":"CADASTRAR","payload":{"cpf":"987.654.321-00","dataNascimento":"1988-07-20","email":"qa.wb030retest.5511900000031@teste.com","nomeCompleto":"Beatriz Retest QA"}},
"dadosCadastro":{"nomeCompleto":"Beatriz Retest QA","cpf":"987.654.321-00","dataNascimento":"1988-07-20","email":"qa.wb030retest.5511900000031@teste.com"}}
```

**CONFIRMADO: correção da máscara de PII funcionou.** `cpf` chegou **intacto** (`"987.654.321-00"`, igual ao enviado) e `email` chegou **intacto** (`"qa.wb030retest.5511900000031@teste.com"`) tanto em `dadosCadastro` quanto em `confirmacaoPendente.payload` — nenhum `***` em nenhum dos dois campos. Isso é uma mudança direta e verificável em relação ao WB030 original, onde os mesmos campos vinham como `"***.***.***-**"` e um e-mail corrompido.

## Passo 5 — "confirmo"
```bash
curl -s -X POST http://localhost:8083/webhook/evolution -H "Content-Type: application/json" \
  -d '{"event":"messages.upsert","instance":"sgsm-bot","data":{"key":{"remoteJid":"5511900000031@s.whatsapp.net","fromMe":false,"id":"WB030R-5"},"pushName":"Beatriz QA Retest","message":{"conversation":"confirmo"},"messageTimestamp":"1700001044"}}'
```
Status: 200.

Redis depois (~5s, chamadas reais a `sgsm`/`ms-sboot-auth` levam mais tempo que os passos anteriores):
```json
{"historico":[..., {"papel":"USUARIO","texto":"confirmo"}, {"papel":"ASSISTENTE","texto":"*Cadastro confirmado!* Seja bem‑vinda, Beatriz Retest QA. 🎉"}],
"confirmacaoPendente":null,
"dadosCadastro":{"nomeCompleto":"Beatriz Retest QA","cpf":"987.654.321-00","dataNascimento":"1988-07-20","email":"qa.wb030retest.5511900000031@teste.com"}}
```

Nota metodológica (herdada de WB030 original, ainda válida): `historico[].ASSISTENTE` é sempre `resp.getRespostaUsuario()` do RAG, não o `respostaFinal` real. A prova real está nos sistemas dependentes e no campo `confirmacaoPendente`, abaixo.

## Verificação real nos sistemas dependentes (prova definitiva de que o cadastro aconteceu de verdade)

### 1. Conta criada no `ms-sboot-auth` — confirmado via geração de OTP real
```bash
curl -s -X POST http://localhost:8081/v1/api/auth/otp/gerar -H "Content-Type: application/json" \
  -d '{"email":"qa.wb030retest.5511900000031@teste.com"}'
```
Resposta: `200 {"otp":"659661","ttlSegundos":300}` — conta existe (se não existisse, `ms-sboot-auth` teria respondido com erro de e-mail não encontrado, como documentado no WB030 original com `401 Credenciais invalidas` para o teste de login).

### 2. OTP real verificado com sucesso (prova que a conta está ativa e funcional)
```bash
curl -s -X POST http://localhost:8081/v1/api/auth/otp/verificar -H "Content-Type: application/json" \
  -d '{"email":"qa.wb030retest.5511900000031@teste.com","otp":"659661"}'
```
Resposta (token redigido):
```json
{"accessToken":"[REDIGIDO]","expiresIn":900,"refreshToken":"[REDIGIDO]","tipo":"Bearer","tipoPerfil":"PACIENTE"}
```
Payload decodificado do JWT (claims não sensíveis): `email:"qa.wb030retest.5511900000031@teste.com"`, `nome:"Beatriz Retest QA"`, `perfil:"PACIENTE"`, `referenciaId:"e7a9e0f4-94af-44c0-8fc7-85d6ed705b66"`, `roles:["PACIENTE"]`.

### 3. Paciente criado no `sgsm` — confirmado via GET real, usando o accessToken real obtido acima
```bash
curl -s -H "Authorization: Bearer [REDIGIDO]" "http://localhost:8080/v1/api/pacientes/e7a9e0f4-94af-44c0-8fc7-85d6ed705b66"
```
Resposta: `200`
```json
{"id":"e7a9e0f4-94af-44c0-8fc7-85d6ed705b66","nome":"Beatriz Retest QA","cpf":"987.654.321-00","dataNascimento":"1988-07-20","email":"qa.wb030retest.5511900000031@teste.com","telefone":"5511900000031","ativo":true,"criadoEm":"2026-08-25T17:11:32.236587Z","atualizadoEm":"2026-08-25T17:11:32.237586Z"}
```
CPF e e-mail **persistidos intactos e válidos** no banco real do `sgsm` — prova conclusiva de que `sgsmClient.criarPaciente` foi chamado com os dados reais (não mascarados) e o `sgsm` aceitou (não houve mais `400 CPF inválido`).

## NOVO ACHADO — item não passa integralmente: `confirmacaoPendente.tipo` nunca chega a `"OTP"`

Apesar do cadastro real ter sido efetivado (paciente + conta), o critério explícito do plano de re-teste — "`confirmacaoPendente.tipo` virou `OTP` no Redis (não voltou a `null` por erro)" — **não foi atendido**: o Redis mostrou `confirmacaoPendente:null` diretamente após o passo 5, nunca passando por `"OTP"`.

Prova real de que isso quebra o fluxo de verdade para o usuário: gerei um OTP real para o e-mail já cadastrado e enviei o código pelo webhook, exatamente como o usuário real faria ao receber a mensagem no WhatsApp:
```bash
curl -s -X POST http://localhost:8081/v1/api/auth/otp/gerar -H "Content-Type: application/json" \
  -d '{"email":"qa.wb030retest.5511900000031@teste.com"}'
# {"otp":"586026","ttlSegundos":300}

curl -s -X POST http://localhost:8083/webhook/evolution -H "Content-Type: application/json" \
  -d '{"event":"messages.upsert","instance":"sgsm-bot","data":{"key":{"remoteJid":"5511900000031@s.whatsapp.net","fromMe":false,"id":"WB030R-6"},"pushName":"Beatriz QA Retest","message":{"conversation":"586026"},"messageTimestamp":"1700001045"}}'
```
Status: 200. Redis depois:
```json
{"historico":[..., {"papel":"USUARIO","texto":"586026"}, {"papel":"ASSISTENTE","texto":"*Olá! Para continuar, preciso identificar você. Por favor, informe seu CPF ou e‑mail.*"}],
"confirmacaoPendente":null,
"accessToken":null,"refreshToken":null,"perfil":null}
```
O bot **não reconheceu o código OTP real** como tal — tratou `"586026"` como mensagem de conversa comum (foi para o RAG, que respondeu pedindo identificação), porque `confirmacaoPendente` já estava `null` antes desta mensagem. `accessToken`/`perfil` continuam `null`: o usuário real, mesmo com cadastro e conta genuinamente criados, **fica sem conseguir se autenticar via bot**.

### Causa raiz (leitura de código só para explicar a mecânica observada — a prova é o teste acima)

Em `BotOrquestradorService.java`, método `processar()`, branch `case CONFIRMAR`:
```java
case CONFIRMAR -> {
    if (sessao.getConfirmacaoPendente() == null) {
        yield "Não há nenhuma ação pendente para confirmar.";
    }
    String resultado = executarConfirmacao(sessao);
    sessao.setConfirmacaoPendente(null);   // <-- linha ~166: sempre nula, incondicionalmente
    yield resultado;
}
```
`executarConfirmacao()` → `executarCadastro()` internamente faz `sessao.setConfirmacaoPendente(new ConfirmacaoPendente("OTP", ...))` (linha ~215) ao concluir cadastro+registro com sucesso — mas essa atribuição é imediatamente sobrescrita para `null` pela linha seguinte no método chamador, ANTES de `sessaoService.salvar(sessao)`. Ou seja, o estado `"OTP"` nunca chega a ser persistido: é setado e desfeito na mesma execução, em memória, antes do save.

Esse é um bug **diferente e independente** do mascaramento de PII (que foi corrigido com sucesso). Ele só ficou visível agora porque, antes da correção, `executarCadastro()` já falhava mais cedo (na chamada a `sgsmClient.criarPaciente`, por causa do CPF mascarado) e caía no `catch`, nunca chegando a executar a linha 215 — então o efeito de sobrescrita da linha 166 era inofensivo/invisível.

## Resultado

**PARCIAL — NÃO aprovado integralmente.** A correção do mascaramento de PII no `sgsm-ia` **funcionou e está confirmada**: CPF e e-mail chegam intactos em `entidades`/`dadosCadastro`/`confirmacaoPendente.payload`, e o cadastro real agora se completa de ponta a ponta — paciente real criado no `sgsm` (`id:e7a9e0f4-94af-44c0-8fc7-85d6ed705b66`) e conta real criada no `ms-sboot-auth`, ambos com CPF/e-mail válidos e íntegros, confirmados via chamadas reais (GET com token real, geração e verificação de OTP real).

Porém o item **não atende integralmente ao critério de aceite**: `confirmacaoPendente.tipo` nunca chega a `"OTP"` no Redis — é sobrescrito para `null` incondicionalmente na branch `CONFIRMAR` do `BotOrquestradorService.processar()` (linha ~166), o que impede o usuário real de autenticar digitando o código OTP recebido de verdade no WhatsApp (reproduzido e confirmado acima: o código real foi enviado ao webhook e tratado como mensagem comum, não como verificação de OTP). Checkbox **não marcado** — recomendo nova rodada de correção especificamente nesta branch (não sobrescrever `confirmacaoPendente` quando a ação executada acabou de defini-lo para um novo estado pendente, ex.: só zerar se `executarConfirmacao` não tiver alterado o tipo, ou fazer `executarCadastro` retornar o novo estado em vez de mutar `sessao` diretamente e deixar o chamador decidir).
