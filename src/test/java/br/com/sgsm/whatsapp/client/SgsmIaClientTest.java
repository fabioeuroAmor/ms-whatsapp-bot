package br.com.sgsm.whatsapp.client;

import br.com.sgsm.whatsapp.config.BotProperties;
import br.com.sgsm.whatsapp.config.RestClientConfig;
import br.com.sgsm.whatsapp.dto.ClassificarRequest;
import br.com.sgsm.whatsapp.service.SistemaAuthService;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Sobe um servidor HTTP local no lugar do sgsm-ia e usa o RestClient de verdade, montado pelo
 * RestClientConfig — assim o teste prova os headers que efetivamente saem na rede.
 */
@ExtendWith(MockitoExtension.class)
class SgsmIaClientTest {

    private static final String CHAVE = "chave-de-teste";

    @Mock
    private SistemaAuthService sistemaAuthService;

    private HttpServer servidor;
    private final Map<String, String> headersRecebidos = new ConcurrentHashMap<>();
    private SgsmIaClient client;

    @BeforeEach
    void setUp() throws Exception {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/ia/whatsapp/classificar", troca -> {
            troca.getRequestHeaders().forEach((nome, valores) -> headersRecebidos.put(nome.toLowerCase(), valores.get(0)));
            byte[] corpo = "{\"intent\":\"CONVERSAR\",\"respostaUsuario\":\"Olá!\",\"requerConfirmacao\":false}"
                    .getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(200, corpo.length);
            troca.getResponseBody().write(corpo);
            troca.close();
        });
        servidor.start();

        var props = new BotProperties(null,
                new BotProperties.SgsmIa("http://127.0.0.1:" + servidor.getAddress().getPort(), CHAVE),
                null, null, null, null, null, null);
        var restClient = new RestClientConfig(props, ObservationRegistry.NOOP).sgsmIaRestClient();
        client = new SgsmIaClient(restClient, sistemaAuthService);
    }

    @AfterEach
    void tearDown() {
        servidor.stop(0);
    }

    private static ClassificarRequest oi() {
        return new ClassificarRequest("oi", List.of(), null, null);
    }

    @Test
    void deveEnviarAChaveDeServicoExigidaPeloSgsmIa() {
        client.classificar(oi(), "token-usuario");

        assertThat(headersRecebidos).containsEntry("x-service-key", CHAVE);
    }

    @Test
    void deveUsarOTokenDaSessaoQuandoOUsuarioEstaIdentificado() {
        var resposta = client.classificar(oi(), "token-usuario");

        assertThat(headersRecebidos).containsEntry("authorization", "Bearer token-usuario");
        assertThat(resposta.getRespostaUsuario()).isEqualTo("Olá!");
        verifyNoInteractions(sistemaAuthService);
    }

    @Test
    void deveUsarOTokenDeSistemaQuandoNaoHaSessaoAutenticada() {
        when(sistemaAuthService.token()).thenReturn("token-sistema");

        client.classificar(oi(), null);

        assertThat(headersRecebidos).containsEntry("authorization", "Bearer token-sistema");
    }
}
