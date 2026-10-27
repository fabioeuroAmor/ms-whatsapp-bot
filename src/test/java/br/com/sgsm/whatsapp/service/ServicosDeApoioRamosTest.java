package br.com.sgsm.whatsapp.service;

import br.com.sgsm.whatsapp.client.AuthClient;
import br.com.sgsm.whatsapp.client.SgsmClient;
import br.com.sgsm.whatsapp.config.BotProperties;
import br.com.sgsm.whatsapp.domain.SessaoBot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

// Sessão no Redis, resolução do canal e token de sistema: ramos que os testes de fluxo não alcançam
class ServicosDeApoioRamosTest {

    @SuppressWarnings("unchecked")
    private static SessaoService sessaoService(ObjectMapper mapper, int historicoMax, ValueOperations<String, String> valores) {
        var redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(valores);
        var props = mock(BotProperties.class);
        var sessao = mock(BotProperties.Sessao.class);
        when(props.sessao()).thenReturn(sessao);
        when(sessao.ttlMinutos()).thenReturn(30);
        when(sessao.historicoMax()).thenReturn(historicoMax);
        return new SessaoService(redis, mapper, props);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sessaoSalvaNoCanalGeralERecuperaDoRedis() throws Exception {
        ValueOperations<String, String> valores = mock(ValueOperations.class);
        var mapper = new ObjectMapper();
        var service = sessaoService(mapper, 10, valores);
        var salva = SessaoBot.builder().numero("5561").nomeUsuario("Ana").consentimentoAceito(true).build();
        when(valores.get("whatsapp:sessao:5561")).thenReturn(mapper.writeValueAsString(salva));

        var lida = service.obter("5561");

        assertThat(lida.getNomeUsuario()).isEqualTo("Ana");
        assertThat(lida.isConsentimentoAceito()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void falhaAoSerializarNaoDerrubaOAtendimento() throws Exception {
        ValueOperations<String, String> valores = mock(ValueOperations.class);
        var mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("falhou") {});
        var service = sessaoService(mapper, 10, valores);

        service.salvar(SessaoBot.builder().numero("5561").build());

        verify(valores, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void historicoComecaVazioEGuardaSoAsUltimasMensagens() {
        var service = sessaoService(new ObjectMapper(), 2, mock(ValueOperations.class));
        var sessao = SessaoBot.builder().numero("5561").build();
        sessao.setHistorico(null);

        service.adicionarHistorico(sessao, "USUARIO", "um");
        service.adicionarHistorico(sessao, "ASSISTENTE", "dois");
        service.adicionarHistorico(sessao, "USUARIO", "três");

        assertThat(sessao.getHistorico()).extracting("texto").containsExactly("dois", "três");
    }

    @Test
    void canalEmBrancoEhOGeralESemRespostaDoSgsmEhDesconhecido() {
        var sgsm = mock(SgsmClient.class);
        var sistema = mock(SistemaAuthService.class);
        var props = mock(BotProperties.class);
        var evolution = mock(BotProperties.Evolution.class);
        when(props.evolution()).thenReturn(evolution);
        when(evolution.instance()).thenReturn("sgsm-bot");
        when(sistema.token()).thenReturn("jwt-bot");
        when(sgsm.resolverCanal("sgsm-z", "jwt-bot")).thenReturn(null);
        var service = new CanalService(sgsm, sistema, props);

        assertThat(service.resolver("  ")).get().satisfies(c -> assertThat(c.geral()).isTrue());
        assertThat(service.resolver("sgsm-z")).isEmpty();
    }

    @Test
    void tokenDeSistemaEhObtidoNaSubida() {
        var auth = mock(AuthClient.class);
        var props = mock(BotProperties.class);
        var sistema = mock(BotProperties.Sistema.class);
        when(props.sistema()).thenReturn(sistema);
        when(sistema.email()).thenReturn("bot@sgsm.local");
        when(sistema.senha()).thenReturn("senha");
        when(auth.login("bot@sgsm.local", "senha")).thenReturn(Map.of("accessToken", "jwt-bot"));
        var service = new SistemaAuthService(auth, props);

        service.autenticarNoStartup();

        assertThat(service.token()).isEqualTo("jwt-bot");
    }
}
