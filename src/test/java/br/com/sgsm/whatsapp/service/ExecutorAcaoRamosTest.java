package br.com.sgsm.whatsapp.service;

import br.com.sgsm.whatsapp.client.AuthClient;
import br.com.sgsm.whatsapp.client.EvolutionApiClient;
import br.com.sgsm.whatsapp.client.SgsmClient;
import br.com.sgsm.whatsapp.domain.SessaoBot;
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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

// Casos de borda do executor: respostas incompletas do sgsm, corpo do 409 ilegível, cancelamento e código
@ExtendWith(MockitoExtension.class)
class ExecutorAcaoRamosTest {

    @Mock private SgsmClient sgsmClient;
    @Mock private AuthClient authClient;
    @Mock private EvolutionApiClient evolutionApiClient;
    @Mock private SistemaAuthService sistemaAuthService;

    private ExecutorAcaoService service;
    private SessaoBot sessao;

    @BeforeEach
    void setUp() {
        service = new ExecutorAcaoService(sgsmClient, authClient, evolutionApiClient, sistemaAuthService, new ObjectMapper());
        sessao = new SessaoBot();
        sessao.setAccessToken("jwt-paciente");
    }

    private static Map<String, Object> payload(String data, String hora) {
        var p = new HashMap<String, Object>();
        p.put("pacienteId", "pac-1");
        p.put("servicoMedicoId", "serv-1");
        p.put("data", data);
        p.put("hora", hora);
        return p;
    }

    private static HttpClientErrorException erro(HttpStatus status, String corpo) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), new HttpHeaders(),
                corpo.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @Test
    void reservaSemRespostaOuSemTokenSegueSemReserva() {
        when(sgsmClient.reservarHorario(any(), anyString()))
                .thenReturn(null)
                .thenReturn(Map.of("reservado", true));

        assertThat(service.reservar(sessao, payload("20/10/2026", "13:00")).situacao())
                .isEqualTo(ExecutorAcaoService.SituacaoReserva.SEM_RESERVA);
        assertThat(service.reservar(sessao, payload("2026-10-20", "13:00:30")).situacao())
                .isEqualTo(ExecutorAcaoService.SituacaoReserva.SEM_RESERVA);
    }

    @Test
    void semDataOuHoraAReservaVaiSemHorarioECanalGeralSemClinica() {
        when(sgsmClient.reservarHorario(any(), anyString())).thenReturn(Map.of());

        service.reservar(sessao, payload(null, "13:00"));
        service.reservar(sessao, payload("20/10/2026", null));

        var req = ArgumentCaptor.forClass(ReservaCreateRequest.class);
        verify(sgsmClient, times(2)).reservarHorario(req.capture(), anyString());
        assertThat(req.getAllValues()).allSatisfy(r -> {
            assertThat(r.dataHoraInicio()).isNull();
            assertThat(r.estabelecimentoId()).isNull();
            assertThat(r.tipo()).isNull();
        });
    }

    @Test
    void conflitoComCorpoVazioIlegivelOuSemListaPedeOutroHorario() {
        when(sgsmClient.reservarHorario(any(), anyString()))
                .thenThrow(erro(HttpStatus.CONFLICT, ""))
                .thenThrow(erro(HttpStatus.CONFLICT, "não é json"))
                .thenThrow(erro(HttpStatus.CONFLICT, "{\"alternativas\":\"nenhuma\"}"));

        for (int i = 0; i < 3; i++) {
            assertThat(service.reservar(sessao, payload("20/10/2026", "13:00")).mensagem())
                    .contains("não encontrei outro próximo");
        }
    }

    @Test
    void alternativasDentroDePropertiesTambemSaoLidas() {
        String corpo = "{\"properties\":{\"alternativas\":[{\"dataHoraInicio\":\"2026-10-20T14:00:00-03:00\"},"
                + "\"lixo\",{\"semData\":true}]}}";
        when(sgsmClient.reservarHorario(any(), anyString())).thenThrow(erro(HttpStatus.CONFLICT, corpo));

        assertThat(service.reservar(sessao, payload("20/10/2026", "13:00")).mensagem())
                .contains("*1.* 20/10 às 14:00").doesNotContain("*2.*");
    }

    @Test
    void agendamento404SemReservaEFalhaNaSegundaTentativaDevolvemMensagemGenerica() {
        when(sgsmClient.criarAgendamento(any(), anyString()))
                .thenThrow(erro(HttpStatus.NOT_FOUND, "{}"))
                .thenThrow(erro(HttpStatus.NOT_FOUND, "{}"))
                .thenThrow(new ResourceAccessException("timeout"));

        assertThat(service.executarAgendamento(sessao, payload("20/10/2026", "13:00")))
                .startsWith("Não foi possível realizar o agendamento");

        var comReserva = payload("20/10/2026", "13:00");
        comReserva.put(ExecutorAcaoService.CHAVE_RESERVA, "tok-expirado");
        assertThat(service.executarAgendamento(sessao, comReserva)).startsWith("Não foi possível realizar o agendamento");
    }

    @Test
    void liberarReservaIgnoraFalhaDoSgsm() {
        doThrow(new ResourceAccessException("fora")).when(sgsmClient).liberarReserva("tok-9", "jwt-paciente");

        service.liberarReserva(sessao, "tok-9");

        verify(sgsmClient).liberarReserva("tok-9", "jwt-paciente");
    }

    @Test
    void cancelamentoComSucessoEComFalha() {
        doNothing().doThrow(new ResourceAccessException("fora")).when(sgsmClient).cancelarAgendamento("ag-1", "jwt-paciente");

        assertThat(service.executarCancelamento(sessao, "ag-1")).isEqualTo("Agendamento cancelado com sucesso.");
        assertThat(service.executarCancelamento(sessao, "ag-1")).startsWith("Não foi possível cancelar");
    }

    @Test
    void codigoPeloCanalGeralEFalhaNoAuthNaoQuebram() {
        when(sistemaAuthService.token()).thenReturn("jwt-bot");
        when(authClient.gerarOtp("m@a.com", "jwt-bot")).thenReturn(Map.of("otp", "654321"));
        when(authClient.gerarOtp("x@a.com", "jwt-bot")).thenThrow(new ResourceAccessException("auth fora"));

        service.enviarOtp("5561", "m@a.com");
        service.enviarOtp("5561", "x@a.com");

        verify(evolutionApiClient).enviarTexto(isNull(), eq("5561"), contains("654321"));
        verify(evolutionApiClient, times(1)).enviarTexto(any(), anyString(), anyString());
    }
}
