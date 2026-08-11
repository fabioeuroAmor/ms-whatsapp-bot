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
        try {
            // numero: "5511999999999" (sem @s.whatsapp.net)
            var body = Map.of("number", numero, "text", texto);
            evolutionClient.post()
                    .uri("/message/sendText/" + props.evolution().instance())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Mensagem enviada para {}: {}", numero,
                    texto.substring(0, Math.min(50, texto.length())));
        } catch (Exception e) {
            log.error("Erro ao enviar mensagem para {}: {}", numero, e.getMessage());
        }
    }
}
