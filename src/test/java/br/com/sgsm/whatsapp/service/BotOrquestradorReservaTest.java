package br.com.sgsm.whatsapp.service;

import br.com.sgsm.whatsapp.client.AuthClient;
import br.com.sgsm.whatsapp.client.EvolutionApiClient;
import br.com.sgsm.whatsapp.client.SgsmClient;
import br.com.sgsm.whatsapp.client.SgsmIaClient;
import br.com.sgsm.whatsapp.domain.ConfirmacaoPendente;
import br.com.sgsm.whatsapp.domain.IntentWhatsApp;
import br.com.sgsm.whatsapp.domain.SessaoBot;
import br.com.sgsm.whatsapp.dto.ClassificarResponse;
import br.com.sgsm.whatsapp.dto.EntidadesExtraidas;
import br.com.sgsm.whatsapp.service.ExecutorAcaoService.ResultadoReserva;
import br.com.sgsm.whatsapp.service.ExecutorAcaoService.SituacaoReserva;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// Pré-reserva do horário no fluxo de agendamento pelo WhatsApp (conflito secretária x bot)
@ExtendWith(MockitoExtension.class)
class BotOrquestradorReservaTest {

    private static final String NUMERO = "5511999990000";

    @Mock
    private SessaoService sessaoService;
    @Mock
    private SgsmIaClient sgsmIaClient;
    @Mock
    private EvolutionApiClient evolutionApiClient;
    @Mock
    private ExecutorAcaoService executorAcaoService;
    @Mock
    private AuthClient authClient;
    @Mock
    private SgsmClient sgsmClient;
    @Mock
    private SistemaAuthService sistemaAuthService;

    private BotOrquestradorService orquestrador;
    private SessaoBot sessao;

    @BeforeEach
    void setUp() {
        orquestrador = new BotOrquestradorService(sessaoService, sgsmIaClient, evolutionApiClient,
                executorAcaoService, authClient, sgsmClient, null, sistemaAuthService, new ObjectMapper());
        sessao = SessaoBot.builder().numero(NUMERO).historico(new ArrayList<>()).consentimentoAceito(true).build();
        sessao.setAccessToken("jwt-paciente");
        sessao.setUsuarioId("pac-1");
        when(sessaoService.obter(NUMERO)).thenReturn(sessao);
    }

    private void classificarComo(IntentWhatsApp intent) {
        var resp = new ClassificarResponse();
        resp.setIntent(intent);
        resp.setRespostaUsuario("Confirma a consulta em 20/10 às 13:00?");
        resp.setRequerConfirmacao(true);
        resp.setDadosFaltantes(List.of());
        var ent = new EntidadesExtraidas();
        ent.setServicoMedicoId("serv-1");
        ent.setData("20/10/2026");
        ent.setHora("13:00");
        resp.setEntidades(ent);
        when(sgsmIaClient.classificar(any(), any())).thenReturn(resp);
    }

    private void pendenteComToken(String token) {
        var payload = new HashMap<String, Object>();
        payload.put(ExecutorAcaoService.CHAVE_RESERVA, token);
        sessao.setConfirmacaoPendente(new ConfirmacaoPendente("AGENDAR", payload));
    }

    @Test
    void agendarPreReservaEGuardaOTokenNaConfirmacao() {
        classificarComo(IntentWhatsApp.AGENDAR);
        when(executorAcaoService.reservar(eq(sessao), any()))
                .thenReturn(new ResultadoReserva(SituacaoReserva.RESERVADO, "tok-1", null));

        orquestrador.processar(NUMERO, "quero 20/10 às 13h", null);

        assertThat(sessao.getConfirmacaoPendente().getTipo()).isEqualTo("AGENDAR");
        assertThat(sessao.getConfirmacaoPendente().getPayload())
                .containsEntry(ExecutorAcaoService.CHAVE_RESERVA, "tok-1")
                .containsEntry("pacienteId", "pac-1");
        verify(evolutionApiClient).enviarTexto(NUMERO, "Confirma a consulta em 20/10 às 13:00?");
    }

    @Test
    void agendarSemReservaSegueParaConfirmacaoSemToken() {
        classificarComo(IntentWhatsApp.AGENDAR);
        when(executorAcaoService.reservar(eq(sessao), any()))
                .thenReturn(new ResultadoReserva(SituacaoReserva.SEM_RESERVA, null, null));

        orquestrador.processar(NUMERO, "quero 20/10 às 13h", null);

        assertThat(sessao.getConfirmacaoPendente().getPayload()).doesNotContainKey(ExecutorAcaoService.CHAVE_RESERVA);
    }

    @Test
    void agendarHorarioOcupadoMostraAlternativasSemPedirConfirmacao() {
        classificarComo(IntentWhatsApp.AGENDAR);
        when(executorAcaoService.reservar(eq(sessao), any()))
                .thenReturn(new ResultadoReserva(SituacaoReserva.INDISPONIVEL, null, "Esse horário acabou de ser ocupado."));

        orquestrador.processar(NUMERO, "quero 20/10 às 13h", null);

        assertThat(sessao.getConfirmacaoPendente()).isNull();
        verify(evolutionApiClient).enviarTexto(NUMERO, "Esse horário acabou de ser ocupado.");
    }

    @Test
    void novoPedidoDeAgendamentoLiberaAReservaAnterior() {
        pendenteComToken("tok-antigo");
        classificarComo(IntentWhatsApp.AGENDAR);
        when(executorAcaoService.reservar(eq(sessao), any()))
                .thenReturn(new ResultadoReserva(SituacaoReserva.RESERVADO, "tok-novo", null));

        orquestrador.processar(NUMERO, "melhor 20/10 às 13h", null);

        verify(executorAcaoService).liberarReserva(sessao, "tok-antigo");
        assertThat(sessao.getConfirmacaoPendente().getPayload())
                .containsEntry(ExecutorAcaoService.CHAVE_RESERVA, "tok-novo");
    }

    @Test
    void recusarLiberaAReserva() {
        pendenteComToken("tok-1");
        classificarComo(IntentWhatsApp.RECUSAR);

        orquestrador.processar(NUMERO, "não", null);

        verify(executorAcaoService).liberarReserva(sessao, "tok-1");
        assertThat(sessao.getConfirmacaoPendente()).isNull();
    }

    @Test
    void confirmarAgendaComOPayloadDaReserva() {
        pendenteComToken("tok-1");
        classificarComo(IntentWhatsApp.CONFIRMAR);
        when(executorAcaoService.executarAgendamento(eq(sessao), any())).thenReturn("Agendamento confirmado com sucesso!");

        orquestrador.processar(NUMERO, "sim", null);

        verify(executorAcaoService).executarAgendamento(sessao, Map.of(ExecutorAcaoService.CHAVE_RESERVA, "tok-1"));
        verify(executorAcaoService, never()).liberarReserva(any(), any());
        assertThat(sessao.getConfirmacaoPendente()).isNull();
    }
}
