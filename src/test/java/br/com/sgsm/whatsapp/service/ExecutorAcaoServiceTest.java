package br.com.sgsm.whatsapp.service;

import br.com.sgsm.whatsapp.client.AuthClient;
import br.com.sgsm.whatsapp.client.EvolutionApiClient;
import br.com.sgsm.whatsapp.client.SgsmClient;
import br.com.sgsm.whatsapp.domain.SessaoBot;
import br.com.sgsm.whatsapp.dto.AgendamentoCreateRequest;
import br.com.sgsm.whatsapp.dto.ReservaCreateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutorAcaoServiceTest {

    private static final String CORPO_409 = """
            {"title":"Horário indisponível","status":409,
             "alternativas":[{"dataHoraInicio":"2026-10-20T14:00:00-03:00","dataHoraFim":"2026-10-20T14:30:00-03:00","duracaoMinutos":30},
                             {"dataHoraInicio":"2026-10-20T15:00:00-03:00","dataHoraFim":"2026-10-20T15:30:00-03:00","duracaoMinutos":30}]}
            """;

    @Mock
    private SgsmClient sgsmClient;
    @Mock
    private AuthClient authClient;
    @Mock
    private EvolutionApiClient evolutionApiClient;
    @Mock
    private SistemaAuthService sistemaAuthService;

    private ExecutorAcaoService service;
    private SessaoBot sessao;

    @BeforeEach
    void setUp() {
        service = new ExecutorAcaoService(sgsmClient, authClient, evolutionApiClient, sistemaAuthService,
                new ObjectMapper());
        sessao = new SessaoBot();
        sessao.setAccessToken("jwt-paciente");
    }

    private Map<String, Object> payload() {
        var payload = new HashMap<String, Object>();
        payload.put("pacienteId", "pac-1");
        payload.put("servicoMedicoId", "serv-1");
        payload.put("data", "20/10/2026");
        payload.put("hora", "13:00");
        return payload;
    }

    private static HttpClientErrorException erro(HttpStatus status, String corpo) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), new HttpHeaders(),
                corpo.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @Test
    void reservarDevolveOTokenQuandoOSgsmSeguraOHorario() {
        when(sgsmClient.reservarHorario(any(), eq("jwt-paciente")))
                .thenReturn(Map.of("token", "tok-1", "reservado", true));

        var resultado = service.reservar(sessao, payload());

        assertThat(resultado.situacao()).isEqualTo(ExecutorAcaoService.SituacaoReserva.RESERVADO);
        assertThat(resultado.token()).isEqualTo("tok-1");
        var captor = ArgumentCaptor.forClass(ReservaCreateRequest.class);
        verify(sgsmClient).reservarHorario(captor.capture(), anyString());
        assertThat(captor.getValue().servicoMedicoId()).isEqualTo("serv-1");
        assertThat(captor.getValue().dataHoraInicio()).isEqualTo("2026-10-20T13:00:00-03:00");
    }

    @Test
    void reservarComHorarioOcupadoDevolveAsAlternativas() {
        when(sgsmClient.reservarHorario(any(), anyString())).thenThrow(erro(HttpStatus.CONFLICT, CORPO_409));

        var resultado = service.reservar(sessao, payload());

        assertThat(resultado.situacao()).isEqualTo(ExecutorAcaoService.SituacaoReserva.INDISPONIVEL);
        assertThat(resultado.mensagem()).contains("*1.* 20/10 às 14:00").contains("*2.* 20/10 às 15:00");
    }

    @Test
    void reservarSemAlternativasPedeOutroHorario() {
        when(sgsmClient.reservarHorario(any(), anyString()))
                .thenThrow(erro(HttpStatus.CONFLICT, "{\"alternativas\":[]}"));

        var resultado = service.reservar(sessao, payload());

        assertThat(resultado.mensagem()).contains("não encontrei outro próximo");
    }

    @Test
    void reservarSegueSemReservaQuandoRedisOuSgsmFalham() {
        when(sgsmClient.reservarHorario(any(), anyString()))
                .thenReturn(Map.of("reservado", false))
                .thenThrow(erro(HttpStatus.BAD_REQUEST, "{}"))
                .thenThrow(new ResourceAccessException("timeout"));

        for (int i = 0; i < 3; i++) {
            var resultado = service.reservar(sessao, payload());
            assertThat(resultado.situacao()).isEqualTo(ExecutorAcaoService.SituacaoReserva.SEM_RESERVA);
            assertThat(resultado.token()).isNull();
        }
    }

    @Test
    void agendamentoEnviaOTokenDaReserva() {
        var payload = payload();
        payload.put(ExecutorAcaoService.CHAVE_RESERVA, "tok-1");

        assertThat(service.executarAgendamento(sessao, payload)).isEqualTo("Agendamento confirmado com sucesso!");

        var captor = ArgumentCaptor.forClass(AgendamentoCreateRequest.class);
        verify(sgsmClient).criarAgendamento(captor.capture(), eq("jwt-paciente"));
        assertThat(captor.getValue().reservaToken()).isEqualTo("tok-1");
        assertThat(captor.getValue().pacienteId()).isEqualTo("pac-1");
    }

    @Test
    void agendamentoComReservaExpiradaTentaDeNovoSemToken() {
        var payload = payload();
        payload.put(ExecutorAcaoService.CHAVE_RESERVA, "tok-expirado");
        when(sgsmClient.criarAgendamento(any(), anyString()))
                .thenThrow(erro(HttpStatus.NOT_FOUND, "{}"))
                .thenReturn(Map.of("id", "ag-1"));

        assertThat(service.executarAgendamento(sessao, payload)).isEqualTo("Agendamento confirmado com sucesso!");

        var captor = ArgumentCaptor.forClass(AgendamentoCreateRequest.class);
        verify(sgsmClient, times(2)).criarAgendamento(captor.capture(), anyString());
        assertThat(captor.getAllValues().get(1).reservaToken()).isNull();
    }

    @Test
    void agendamentoComReservaExpiradaEHorarioTomadoMostraAlternativas() {
        var payload = payload();
        payload.put(ExecutorAcaoService.CHAVE_RESERVA, "tok-expirado");
        when(sgsmClient.criarAgendamento(any(), anyString()))
                .thenThrow(erro(HttpStatus.NOT_FOUND, "{}"))
                .thenThrow(erro(HttpStatus.CONFLICT, CORPO_409));

        assertThat(service.executarAgendamento(sessao, payload)).contains("acabou de ser ocupado");
    }

    @Test
    void agendamentoEmConflitoMostraAlternativas() {
        when(sgsmClient.criarAgendamento(any(), anyString())).thenThrow(erro(HttpStatus.CONFLICT, CORPO_409));

        assertThat(service.executarAgendamento(sessao, payload())).contains("*1.* 20/10 às 14:00");
        verify(sgsmClient, times(1)).criarAgendamento(any(), anyString());
    }

    @Test
    void agendamentoComOutrasFalhasDevolveMensagemGenerica() {
        when(sgsmClient.criarAgendamento(any(), anyString()))
                .thenThrow(erro(HttpStatus.BAD_REQUEST, "{}"))
                .thenThrow(new ResourceAccessException("timeout"));

        assertThat(service.executarAgendamento(sessao, payload())).startsWith("Não foi possível realizar o agendamento");
        assertThat(service.executarAgendamento(sessao, payload())).startsWith("Não foi possível realizar o agendamento");

        var payloadInvalido = payload();
        payloadInvalido.put("data", "amanhã");
        assertThat(service.executarAgendamento(sessao, payloadInvalido)).startsWith("Não foi possível");
    }

    // Rodada 8 do QA: cadastro pelo bot com nascimento "15/04/1991" dava 400 no sgsm
    @Test
    void dataDeNascimentoDoPacienteViraIso() {
        assertThat(ExecutorAcaoService.dataIso("15/04/1991")).isEqualTo("1991-04-15");
        assertThat(ExecutorAcaoService.dataIso(" 1991-04-15 ")).isEqualTo("1991-04-15");
        assertThat(ExecutorAcaoService.dataIso("abril de 91")).isEqualTo("abril de 91");
        assertThat(ExecutorAcaoService.dataIso(null)).isNull();
    }

    @Test
    void liberarReservaEhMelhorEsforco() {
        doThrow(new ResourceAccessException("timeout")).when(sgsmClient).liberarReserva("tok-1", "jwt-paciente");

        service.liberarReserva(sessao, "tok-1");
        service.liberarReserva(sessao, null);

        verify(sgsmClient, times(1)).liberarReserva(anyString(), anyString());
    }
}
