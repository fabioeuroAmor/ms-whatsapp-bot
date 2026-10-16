package br.com.sgsm.whatsapp.service;

import br.com.sgsm.whatsapp.client.AuthClient;
import br.com.sgsm.whatsapp.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SistemaAuthServiceTest {

    @Mock
    private AuthClient authClient;

    private SistemaAuthService service;

    @BeforeEach
    void setUp() {
        var props = new BotProperties(null, null, null, null,
                new BotProperties.Sistema("bot@sgsm.local", "senha"), null, null, null);
        service = new SistemaAuthService(authClient, props);
    }

    @Test
    void renovarTokenDeveGuardarOAccessTokenDoLogin() {
        when(authClient.login("bot@sgsm.local", "senha")).thenReturn(Map.of("accessToken", "token-1"));

        service.renovarToken();

        assertThat(service.token()).isEqualTo("token-1");
        verify(authClient, times(1)).login(anyString(), anyString());
    }

    @Test
    void falhaNoLoginNaoDeveDerrubarOBot() {
        when(authClient.login(anyString(), anyString())).thenThrow(new RuntimeException("auth fora do ar"));

        service.renovarToken();

        verify(authClient).login("bot@sgsm.local", "senha");
    }

    // Regressão: se o login do startup falhou (auth ainda subindo), o bot mandava "Bearer null"
    // ao sgsm-ia por até 10 minutos e o atendimento recebia 401
    @Test
    void tokenNuloDeveRefazerOLoginNaHora() {
        when(authClient.login(anyString(), anyString()))
                .thenThrow(new RuntimeException("auth ainda subindo"))
                .thenReturn(Map.of("accessToken", "token-2"));
        service.renovarToken();

        assertThat(service.token()).isEqualTo("token-2");
        verify(authClient, times(2)).login(anyString(), anyString());
    }

    @Test
    void tokenPresenteNaoDeveRefazerLogin() {
        when(authClient.login(anyString(), anyString())).thenReturn(Map.of("accessToken", "token-1"));
        service.renovarToken();

        service.token();
        service.token();

        verify(authClient, times(1)).login(anyString(), anyString());
    }
}
