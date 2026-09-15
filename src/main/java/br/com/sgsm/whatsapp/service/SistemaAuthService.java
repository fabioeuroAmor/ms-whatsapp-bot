package br.com.sgsm.whatsapp.service;

import br.com.sgsm.whatsapp.client.AuthClient;
import br.com.sgsm.whatsapp.config.BotProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

// Mantém o bot autenticado como usuário de sistema (perfil DESENVOLVEDOR) sem depender de um
// token fixo configurado manualmente. O access token do ms-sboot-auth expira em 15 minutos,
// então renovamos a cada 10 — sempre com folga antes do vencimento — logando de novo com as
// credenciais da conta de sistema em vez de guardar um JWT estático.
@Service
public class SistemaAuthService {

    private static final Logger log = LoggerFactory.getLogger(SistemaAuthService.class);

    private final AuthClient authClient;
    private final BotProperties props;
    private final AtomicReference<String> tokenAtual = new AtomicReference<>();

    public SistemaAuthService(AuthClient authClient, BotProperties props) {
        this.authClient = authClient;
        this.props = props;
    }

    @PostConstruct
    public void autenticarNoStartup() {
        renovarToken();
    }

    // initialDelay evita duplicar o login do @PostConstruct: sem ele, fixedRate tambem
    // dispara uma primeira execucao imediata na inicializacao do scheduler.
    @Scheduled(initialDelay = 10 * 60 * 1000L, fixedRate = 10 * 60 * 1000L)
    public void renovarToken() {
        try {
            var resposta = authClient.login(props.sistema().email(), props.sistema().senha());
            tokenAtual.set((String) resposta.get("accessToken"));
            log.info("Token de sistema (bot) renovado com sucesso.");
        } catch (Exception e) {
            log.error("Falha ao renovar token de sistema do bot: {}", e.getMessage());
        }
    }

    public String token() {
        return tokenAtual.get();
    }
}
