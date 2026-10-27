package br.com.sgsm.whatsapp.client;

import br.com.sgsm.whatsapp.config.BotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class EvolutionApiClient {

    private static final Logger log = LoggerFactory.getLogger(EvolutionApiClient.class);

    private final RestClient evolutionClient;
    private final BotProperties props;

    public EvolutionApiClient(@Qualifier("evolutionClient") RestClient evolutionClient,
                              BotProperties props) {
        this.evolutionClient = evolutionClient;
        this.props = props;
    }

    public void enviarTexto(String numero, String texto) {
        enviarTexto(null, numero, texto);
    }

    // instancia: número da clínica que recebeu a conversa (responde pelo mesmo número); null = canal geral
    public void enviarTexto(String instancia, String numero, String texto) {
        try {
            // numero: "5511999999999" (sem @s.whatsapp.net)
            var body = Map.of("number", numero, "text", texto);
            evolutionClient.post()
                    .uri("/message/sendText/" + (instancia != null ? instancia : props.evolution().instance()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            // só o tamanho: o texto pode ter código de verificação (OTP) ou dado pessoal
            log.debug("Mensagem enviada para {} ({} caracteres)", numero, texto.length());
        } catch (Exception e) {
            log.error("Erro ao enviar mensagem para {}: {}", numero, e.getMessage());
        }
    }
}
