package br.com.sgsm.whatsapp.service;

import br.com.sgsm.whatsapp.client.AuthClient;
import br.com.sgsm.whatsapp.config.BotProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class BotSistemaTokenService {

    private static final Logger log = LoggerFactory.getLogger(BotSistemaTokenService.class);

    private final AuthClient authClient;
    private final BotProperties props;

    private final AtomicReference<String> accessToken = new AtomicReference<>();
    private final AtomicReference<String> refreshToken = new AtomicReference<>();

    public BotSistemaTokenService(AuthClient authClient, BotProperties props) {
        this.authClient = authClient;
        this.props = props;
    }

    @PostConstruct
    public void inicializar() {
        var sistema = props.sistema();
        if (sistema.email() != null && !sistema.email().isBlank()
                && sistema.senha() != null && !sistema.senha().isBlank()) {
            try {
                login();
                log.info("Token do sistema inicializado para {}", sistema.email());
            } catch (Exception e) {
                log.warn("Falha ao obter token do sistema na inicialização: {}. Usando JWT estático como fallback.", e.getMessage());
                accessToken.set(sistema.jwt());
            }
        } else {
            log.info("Credenciais do sistema não configuradas — usando JWT estático (BOT_SISTEMA_JWT).");
            accessToken.set(sistema.jwt());
        }
    }

    // Renova a cada 10 minutos (JWT expira em 15)
    @Scheduled(fixedDelay = 600_000)
    public void renovar() {
        var sistema = props.sistema();
        if (sistema.email() == null || sistema.email().isBlank()) return;

        try {
            String rt = refreshToken.get();
            if (rt != null) {
                Map<String, Object> resp = authClient.refresh(rt);
                accessToken.set((String) resp.get("accessToken"));
                log.debug("Token do sistema renovado via refresh.");
            } else {
                login();
            }
        } catch (Exception e) {
            log.warn("Refresh do token do sistema falhou, fazendo login completo: {}", e.getMessage());
            try {
                login();
            } catch (Exception ex) {
                log.error("Falha crítica ao renovar token do sistema: {}", ex.getMessage());
            }
        }
    }

    public String getToken() {
        String token = accessToken.get();
        // Se nulo e há configuração estática, usa fallback
        return token != null ? token : props.sistema().jwt();
    }

    private void login() {
        var sistema = props.sistema();
        Map<String, Object> resp = authClient.login(sistema.email(), sistema.senha());
        accessToken.set((String) resp.get("accessToken"));
        refreshToken.set((String) resp.get("refreshToken"));
    }
}
