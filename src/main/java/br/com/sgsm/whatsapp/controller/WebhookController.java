package br.com.sgsm.whatsapp.controller;

import br.com.sgsm.whatsapp.config.BotProperties;
import br.com.sgsm.whatsapp.dto.EvolutionWebhookPayload;
import br.com.sgsm.whatsapp.service.BotOrquestradorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhook")
@Tag(name = "Webhook", description = "Recebe eventos da Evolution API")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final BotOrquestradorService botOrquestradorService;
    private final BotProperties props;

    public WebhookController(BotOrquestradorService botOrquestradorService,
                             BotProperties props) {
        this.botOrquestradorService = botOrquestradorService;
        this.props = props;
    }

    @PostMapping("/evolution")
    @Operation(summary = "Recebe eventos messages.upsert da Evolution API e processa mensagens")
    public ResponseEntity<Void> receberEvento(@RequestBody EvolutionWebhookPayload payload) {
        try {
            // Processa apenas mensagens recebidas (não enviadas pelo bot)
            if (!"messages.upsert".equals(payload.getEvent())) {
                return ResponseEntity.ok().build();
            }
            if (payload.getData() == null || payload.getData().getKey() == null) {
                return ResponseEntity.ok().build();
            }
            // Ignora mensagens enviadas pelo próprio bot
            if (Boolean.TRUE.equals(payload.getData().getKey().getFromMe())) {
                return ResponseEntity.ok().build();
            }

            String remoteJid = payload.getData().getKey().getRemoteJid();
            // Ignora grupos (apenas individuais terminam em @s.whatsapp.net)
            if (remoteJid == null || !remoteJid.endsWith("@s.whatsapp.net")) {
                return ResponseEntity.ok().build();
            }
            String numero = remoteJid.replace("@s.whatsapp.net", "");

            // Extrai texto da mensagem (suporta conversation e extendedTextMessage)
            String texto = null;
            if (payload.getData().getMessage() != null) {
                texto = payload.getData().getMessage().getConversation();
                if (texto == null && payload.getData().getMessage().getExtendedTextMessage() != null) {
                    texto = payload.getData().getMessage().getExtendedTextMessage().getText();
                }
            }
            if (texto == null || texto.isBlank()) {
                return ResponseEntity.ok().build();
            }

            String nomeUsuario = payload.getData().getPushName();
            log.debug("Webhook recebido de {}: '{}'", numero,
                    texto.substring(0, Math.min(50, texto.length())));

            // Processa em virtual thread para não bloquear o webhook e permitir resposta rápida
            final String textoFinal = texto;
            final String nomeFinal = nomeUsuario;
            Thread.ofVirtual().start(() -> botOrquestradorService.processar(numero, textoFinal, nomeFinal));

        } catch (Exception e) {
            log.error("Erro ao processar webhook: {}", e.getMessage(), e);
        }
        // Sempre retorna 200 para a Evolution API não retentar o envio
        return ResponseEntity.ok().build();
    }
}
