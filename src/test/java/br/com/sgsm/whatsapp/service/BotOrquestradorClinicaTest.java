package br.com.sgsm.whatsapp.service;

import br.com.sgsm.whatsapp.client.AuthClient;
import br.com.sgsm.whatsapp.client.EvolutionApiClient;
import br.com.sgsm.whatsapp.client.SgsmClient;
import br.com.sgsm.whatsapp.client.SgsmIaClient;
import br.com.sgsm.whatsapp.config.BotProperties;
import br.com.sgsm.whatsapp.domain.ConfirmacaoPendente;
import br.com.sgsm.whatsapp.domain.IntentWhatsApp;
import br.com.sgsm.whatsapp.domain.SessaoBot;
import br.com.sgsm.whatsapp.dto.ClassificarRequest;
import br.com.sgsm.whatsapp.dto.ClassificarResponse;
import br.com.sgsm.whatsapp.dto.EntidadesExtraidas;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// WhatsApp por clínica: a conversa que entra pelo número de uma clínica fica ligada a ela
@ExtendWith(MockitoExtension.class)
class BotOrquestradorClinicaTest {

    private static final String NUMERO = "5511900000002";
    private static final CanalService.Canal CLINICA_A = new CanalService.Canal("sgsm-a", "clinica-a", "Clinica A");
    private static final CanalService.Canal GERAL = new CanalService.Canal(null, null, null);

    @Mock private SessaoService sessaoService;
    @Mock private SgsmIaClient sgsmIaClient;
    @Mock private EvolutionApiClient evolutionApiClient;
    @Mock private ExecutorAcaoService executorAcaoService;
    @Mock private AuthClient authClient;
    @Mock private SgsmClient sgsmClient;
    @Mock private SistemaAuthService sistemaAuthService;
    @Mock private BotProperties props;
    @Mock private BotProperties.Consentimento consentimento;

    private BotOrquestradorService orquestrador;
    private SessaoBot sessao;

    @BeforeEach
    void setUp() {
        orquestrador = new BotOrquestradorService(sessaoService, sgsmIaClient, evolutionApiClient,
                executorAcaoService, authClient, sgsmClient, props, sistemaAuthService, new ObjectMapper());
        sessao = SessaoBot.builder().numero(NUMERO).historico(new ArrayList<>()).consentimentoAceito(false).build();
        lenient().when(sistemaAuthService.token()).thenReturn("jwt-bot");
        lenient().when(props.consentimento()).thenReturn(consentimento);
        lenient().when(consentimento.mensagem()).thenReturn("Termo LGPD — responda ACEITO");
    }

    private void classificarComo(IntentWhatsApp intent, String resposta) {
        var resp = new ClassificarResponse();
        resp.setIntent(intent);
        resp.setRespostaUsuario(resposta);
        resp.setDadosFaltantes(List.of());
        resp.setEntidades(new EntidadesExtraidas());
        when(sgsmIaClient.classificar(any(), any())).thenReturn(resp);
    }

    @Test
    void primeiroContatoNoNumeroDaClinicaPedeOTermoERespondePeloMesmoNumero() {
        when(sessaoService.obter(NUMERO, "sgsm-a")).thenReturn(sessao);
        when(sgsmClient.consentimentoVigente(NUMERO, "clinica-a", "jwt-bot")).thenReturn(false);

        orquestrador.processar(NUMERO, "oi", "Maria", CLINICA_A);

        assertThat(sessao.getInstancia()).isEqualTo("sgsm-a");
        assertThat(sessao.getEstabelecimentoId()).isEqualTo("clinica-a");
        verify(evolutionApiClient).enviarTexto("sgsm-a", NUMERO, "Termo LGPD — responda ACEITO");
        verify(evolutionApiClient, never()).enviarTexto(anyString(), anyString());
    }

    @Test
    void aceiteRegistraOConsentimentoNaClinicaECriaOLeadUmaVez() {
        when(sessaoService.obter(NUMERO, "sgsm-a")).thenReturn(sessao);
        when(sgsmClient.consentimentoVigente(any(), any(), any())).thenReturn(false);
        when(sessaoService.leadDe("clinica-a", NUMERO)).thenReturn(null);
        when(sgsmIaClient.criarLead("Maria", NUMERO, "clinica-a")).thenReturn("lead-1");

        orquestrador.processar(NUMERO, "ACEITO", "Maria", CLINICA_A);

        assertThat(sessao.isConsentimentoAceito()).isTrue();
        verify(sgsmClient).registrarConsentimento(NUMERO, "clinica-a", BotOrquestradorService.VERSAO_TERMO_WHATSAPP, "jwt-bot");
        verify(sessaoService).guardarLead("clinica-a", NUMERO, "lead-1");
        verify(evolutionApiClient).enviarTexto(eq("sgsm-a"), eq(NUMERO), contains("Como posso ajudar"));
    }

    @Test
    void consentimentoJaDadoAClinicaNaoEhPedidoDeNovoEAIaRecebeAClinica() {
        when(sessaoService.obter(NUMERO, "sgsm-a")).thenReturn(sessao);
        when(sgsmClient.consentimentoVigente(NUMERO, "clinica-a", "jwt-bot")).thenReturn(true);
        classificarComo(IntentWhatsApp.CONVERSAR, "Olá! Sou o assistente da Clinica A.");

        orquestrador.processar(NUMERO, "oi", null, CLINICA_A);

        var pedido = ArgumentCaptor.forClass(ClassificarRequest.class);
        verify(sgsmIaClient).classificar(pedido.capture(), any());
        assertThat(pedido.getValue().estabelecimentoId()).isEqualTo("clinica-a");
        assertThat(pedido.getValue().estabelecimentoNome()).isEqualTo("Clinica A");
        verify(evolutionApiClient).enviarTexto("sgsm-a", NUMERO, "Olá! Sou o assistente da Clinica A.");
        verify(sgsmClient, never()).registrarConsentimento(any(), any(), any(), any());
    }

    @Test
    void falhasDoSgsmNoConsentimentoENoLeadNaoTravamAConversa() {
        when(sessaoService.obter(NUMERO, "sgsm-a")).thenReturn(sessao);
        when(sgsmClient.consentimentoVigente(any(), any(), any())).thenThrow(new ResourceAccessException("fora"));
        doThrow(new ResourceAccessException("fora")).when(sgsmClient).registrarConsentimento(any(), any(), any(), any());
        when(sgsmIaClient.criarLead(any(), any(), any())).thenThrow(new ResourceAccessException("fora"));

        orquestrador.processar(NUMERO, "aceito", null, CLINICA_A);

        assertThat(sessao.isConsentimentoAceito()).isTrue();
        verify(sessaoService, never()).guardarLead(any(), any(), any());
        verify(sgsmIaClient).criarLead(eq("Contato WhatsApp"), eq(NUMERO), eq("clinica-a"));
    }

    @Test
    void canalGeralSemClinicaPerguntaAClinicaAntesDeAgendarEGuardaAEscolha() {
        sessao.setConsentimentoAceito(true);
        sessao.setAccessToken("jwt-paciente");
        when(sessaoService.obter(NUMERO, null)).thenReturn(sessao);
        classificarComo(IntentWhatsApp.AGENDAR, "Confirma?");
        when(sgsmClient.listarEstabelecimentosAtivos("jwt-bot")).thenReturn(List.of(
                Map.of("id", "clinica-a", "nome", "Clinica A"), Map.of("id", "clinica-b", "nome", "Clinica B")));

        orquestrador.processar(NUMERO, "quero agendar", null, GERAL);

        assertThat(sessao.getConfirmacaoPendente().getTipo()).isEqualTo("ESCOLHER_CLINICA");
        verify(evolutionApiClient).enviarTexto(eq(NUMERO), contains("*2.* Clinica B"));
        verify(executorAcaoService, never()).reservar(any(), any());

        orquestrador.processar(NUMERO, "abc", null, GERAL);
        verify(evolutionApiClient).enviarTexto(eq(NUMERO), contains("Não entendi"));

        orquestrador.processar(NUMERO, "2", null, GERAL);
        assertThat(sessao.getEstabelecimentoId()).isEqualTo("clinica-b");
        assertThat(sessao.getConfirmacaoPendente()).isNull();
        verify(evolutionApiClient).enviarTexto(eq(NUMERO), contains("Vou agendar na *Clinica B*"));
    }

    @Test
    void canalGeralSemClinicasOuComFalhaAvisaOPaciente() {
        sessao.setConsentimentoAceito(true);
        sessao.setAccessToken("jwt-paciente");
        when(sessaoService.obter(NUMERO, null)).thenReturn(sessao);
        classificarComo(IntentWhatsApp.AGENDAR, "Confirma?");
        when(sgsmClient.listarEstabelecimentosAtivos("jwt-bot"))
                .thenReturn(List.of())
                .thenThrow(new ResourceAccessException("fora"));

        orquestrador.processar(NUMERO, "quero agendar", null, GERAL);
        orquestrador.processar(NUMERO, "quero agendar", null, GERAL);

        verify(evolutionApiClient).enviarTexto(eq(NUMERO), contains("não há clínicas disponíveis"));
        verify(evolutionApiClient).enviarTexto(eq(NUMERO), contains("Não consegui carregar as clínicas"));
    }

    @Test
    void agendamentoPeloNumeroDaClinicaLevaAClinicaNoPedido() {
        sessao.setConsentimentoAceito(true);
        sessao.setAccessToken("jwt-paciente");
        sessao.setUsuarioId("pac-1");
        when(sessaoService.obter(NUMERO, "sgsm-a")).thenReturn(sessao);
        var resp = new ClassificarResponse();
        resp.setIntent(IntentWhatsApp.AGENDAR);
        resp.setRespostaUsuario("Confirma?");
        resp.setRequerConfirmacao(true);
        resp.setDadosFaltantes(List.of());
        var ent = new EntidadesExtraidas();
        ent.setServicoMedicoId("serv-1");
        ent.setData("21/10/2026");
        ent.setHora("10:00");
        resp.setEntidades(ent);
        when(sgsmIaClient.classificar(any(), any())).thenReturn(resp);
        when(executorAcaoService.reservar(eq(sessao), any()))
                .thenReturn(new ExecutorAcaoService.ResultadoReserva(ExecutorAcaoService.SituacaoReserva.SEM_RESERVA, null, null));

        orquestrador.processar(NUMERO, "quero dia 21 às 10h", null, CLINICA_A);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(executorAcaoService).reservar(eq(sessao), payload.capture());
        assertThat(payload.getValue()).containsEntry("estabelecimentoId", "clinica-a");
    }

    @Test
    void loginPeloNumeroDaClinicaVinculaOPacienteEConverteOLead() {
        sessao.setConsentimentoAceito(true);
        sessao.setConfirmacaoPendente(new ConfirmacaoPendente("OTP", new HashMap<>(Map.of("email", "m@a.com"))));
        when(sessaoService.obter(NUMERO, "sgsm-a")).thenReturn(sessao);
        when(authClient.verificarOtp("m@a.com", "123456"))
                .thenReturn(Map.of("accessToken", "jwt", "refreshToken", "r", "tipoPerfil", "PACIENTE"));
        when(authClient.me("jwt")).thenReturn(Map.of("referenciaId", "pac-1"));
        when(sessaoService.leadDe("clinica-a", NUMERO)).thenReturn("lead-1");

        orquestrador.processar(NUMERO, "123456", null, CLINICA_A);

        verify(sgsmClient).vincularPaciente("pac-1", "clinica-a", NUMERO, "jwt-bot");
        verify(sgsmIaClient).converterLead("lead-1");
        verify(sessaoService).guardarLead("clinica-a", NUMERO, BotOrquestradorService.LEAD_CONVERTIDO);
        verify(evolutionApiClient).enviarTexto(eq("sgsm-a"), eq(NUMERO), contains("Conta ativada"));
    }

    @Test
    void leadJaConvertidoEFalhaNoVinculoNaoAtrapalhamOLogin() {
        sessao.setConsentimentoAceito(true);
        sessao.setConfirmacaoPendente(new ConfirmacaoPendente("OTP", new HashMap<>(Map.of("email", "m@a.com"))));
        when(sessaoService.obter(NUMERO, "sgsm-a")).thenReturn(sessao);
        when(authClient.verificarOtp("m@a.com", "123456"))
                .thenReturn(Map.of("accessToken", "jwt", "refreshToken", "r", "tipoPerfil", "PACIENTE"));
        when(authClient.me("jwt")).thenReturn(Map.of("referenciaId", "pac-1"));
        doThrow(new ResourceAccessException("fora")).when(sgsmClient).vincularPaciente(any(), any(), any(), any());
        when(sessaoService.leadDe("clinica-a", NUMERO)).thenReturn(BotOrquestradorService.LEAD_CONVERTIDO);

        orquestrador.processar(NUMERO, "123456", null, CLINICA_A);

        verify(sgsmIaClient, never()).converterLead(any());
        assertThat(sessao.getAccessToken()).isEqualTo("jwt");
    }
}
