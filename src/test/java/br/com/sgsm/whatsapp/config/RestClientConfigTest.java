package br.com.sgsm.whatsapp.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

// QA WA-B09: a conversão do lead é PATCH; o cliente HTTP do bot precisa enviá-lo de verdade
class RestClientConfigTest {

    private HttpServer servidor;
    private final AtomicReference<String> recebido = new AtomicReference<>();

    @BeforeEach
    void subir() throws Exception {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/crm/leads/l1/status", troca -> {
            recebido.set(troca.getRequestMethod() + " "
                    + new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            troca.sendResponseHeaders(204, -1);
            troca.close();
        });
        servidor.start();
    }

    @AfterEach
    void parar() {
        servidor.stop(0);
    }

    @Test
    void enviaPatchComCorpo() {
        var cliente = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + servidor.getAddress().getPort())
                .requestFactory(RestClientConfig.factory(Duration.ofSeconds(2), Duration.ofSeconds(2)))
                .build();

        var resposta = cliente.patch().uri("/crm/leads/{id}/status", "l1")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"status\":\"CONVERTIDO\"}")
                .retrieve().toBodilessEntity();

        assertThat(resposta.getStatusCode().value()).isEqualTo(204);
        assertThat(recebido.get()).isEqualTo("PATCH {\"status\":\"CONVERTIDO\"}");
    }
}
