package br.com.sgsm.whatsapp.service;

import br.com.sgsm.whatsapp.client.SgsmClient;
import br.com.sgsm.whatsapp.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CanalServiceTest {

    @Mock private SgsmClient sgsmClient;
    @Mock private SistemaAuthService sistemaAuthService;
    @Mock private BotProperties props;
    @Mock private BotProperties.Evolution evolution;

    private final MutableClock relogio = new MutableClock(Instant.parse("2026-10-26T10:00:00Z"));
    private CanalService service;

    static class MutableClock extends Clock {
        private Instant agora;
        MutableClock(Instant agora) { this.agora = agora; }
        void avancar(long segundos) { agora = agora.plusSeconds(segundos); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return agora; }
    }

    @BeforeEach
    void setUp() {
        lenient().when(props.evolution()).thenReturn(evolution);
        lenient().when(evolution.instance()).thenReturn("sgsm-bot");
        lenient().when(sistemaAuthService.token()).thenReturn("jwt-bot");
        service = new CanalService(sgsmClient, sistemaAuthService, props, relogio);
    }

    @Test
    void instanciaGeralNaoConsultaOSgsm() {
        assertThat(service.resolver("sgsm-bot")).get().satisfies(c -> assertThat(c.geral()).isTrue());
        assertThat(service.resolver(null)).get().satisfies(c -> assertThat(c.geral()).isTrue());
        verifyNoInteractions(sgsmClient);
    }

    @Test
    void instanciaDaClinicaVemDoSgsmEFicaEmCachePorUmMinuto() {
        when(sgsmClient.resolverCanal("sgsm-abc", "jwt-bot"))
                .thenReturn(Map.of("estabelecimentoId", "clinica-a", "estabelecimentoNome", "Clinica A"));

        var canal = service.resolver("sgsm-abc").orElseThrow();
        service.resolver("sgsm-abc");

        assertThat(canal.geral()).isFalse();
        assertThat(canal.estabelecimentoId()).isEqualTo("clinica-a");
        assertThat(canal.estabelecimentoNome()).isEqualTo("Clinica A");
        verify(sgsmClient, times(1)).resolverCanal(any(), any());

        relogio.avancar(CanalService.VALIDADE_CACHE_SEGUNDOS + 1);
        service.resolver("sgsm-abc");
        verify(sgsmClient, times(2)).resolverCanal(any(), any());
    }

    @Test
    void instanciaDesconhecidaEhIgnoradaESgsmForaNaoFicaEmCache() {
        when(sgsmClient.resolverCanal("sgsm-x", "jwt-bot")).thenThrow(HttpClientErrorException.create(
                HttpStatus.NOT_FOUND, "nf", new HttpHeaders(), new byte[0], null));
        when(sgsmClient.resolverCanal("sgsm-y", "jwt-bot"))
                .thenThrow(new ResourceAccessException("fora"))
                .thenReturn(Map.of("estabelecimentoId", "clinica-b"));
        when(sgsmClient.resolverCanal("sgsm-z", "jwt-bot")).thenReturn(Map.of());

        assertThat(service.resolver("sgsm-x")).isEmpty();
        assertThat(service.resolver("sgsm-z")).isEmpty();
        assertThat(service.resolver("sgsm-y")).isEmpty();
        assertThat(service.resolver("sgsm-y")).get().satisfies(c -> assertThat(c.estabelecimentoNome()).isNull());
    }
}
