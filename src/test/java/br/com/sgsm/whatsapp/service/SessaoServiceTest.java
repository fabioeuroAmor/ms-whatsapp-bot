package br.com.sgsm.whatsapp.service;

import br.com.sgsm.whatsapp.config.BotProperties;
import br.com.sgsm.whatsapp.domain.SessaoBot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// WhatsApp por clínica: uma conversa por número + clínica
@ExtendWith(MockitoExtension.class)
class SessaoServiceTest {

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valores;
    @Mock private BotProperties props;
    @Mock private BotProperties.Sessao sessaoProps;

    private SessaoService service;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valores);
        lenient().when(props.sessao()).thenReturn(sessaoProps);
        lenient().when(sessaoProps.ttlMinutos()).thenReturn(30);
        service = new SessaoService(redis, new ObjectMapper(), props);
    }

    @Test
    void chaveSeparaClinicasEMantemOCanalGeralComoAntes() {
        assertThat(SessaoService.chave("5561", null)).isEqualTo("whatsapp:sessao:5561");
        assertThat(SessaoService.chave("5561", "sgsm-a")).isEqualTo("whatsapp:sessao:sgsm-a:5561");
    }

    @Test
    void sessaoNovaNasceNoCanalDaMensagemESalvaNaChaveDele() {
        SessaoBot sessao = service.obter("5561", "sgsm-a");

        assertThat(sessao.getInstancia()).isEqualTo("sgsm-a");
        service.salvar(sessao);
        verify(valores).set(eq("whatsapp:sessao:sgsm-a:5561"), anyString(), eq(Duration.ofMinutes(30)));
    }

    @Test
    void sessaoCorrompidaRecomecaNoMesmoCanalELeadFicaPorClinica() {
        when(valores.get("whatsapp:sessao:sgsm-a:5561")).thenReturn("{json inválido");
        assertThat(service.obter("5561", "sgsm-a").getInstancia()).isEqualTo("sgsm-a");

        when(valores.get("whatsapp:lead:clinica-a:5561")).thenReturn("lead-1");
        assertThat(service.leadDe("clinica-a", "5561")).isEqualTo("lead-1");
        service.guardarLead("clinica-a", "5561", "lead-2");
        verify(valores).set(eq("whatsapp:lead:clinica-a:5561"), eq("lead-2"), any(Duration.class));
    }
}
