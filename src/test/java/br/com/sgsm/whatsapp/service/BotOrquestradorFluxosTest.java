package br.com.sgsm.whatsapp.service;

import br.com.sgsm.whatsapp.client.AuthClient;
import br.com.sgsm.whatsapp.client.EvolutionApiClient;
import br.com.sgsm.whatsapp.client.SgsmClient;
import br.com.sgsm.whatsapp.client.SgsmIaClient;
import br.com.sgsm.whatsapp.config.BotProperties;
import br.com.sgsm.whatsapp.domain.ConfirmacaoPendente;
import br.com.sgsm.whatsapp.domain.IntentWhatsApp;
import br.com.sgsm.whatsapp.domain.SessaoBot;
import br.com.sgsm.whatsapp.dto.AuthRegistrarRequest;
import br.com.sgsm.whatsapp.dto.ClassificarResponse;
import br.com.sgsm.whatsapp.dto.EntidadesExtraidas;
import br.com.sgsm.whatsapp.dto.PacienteCreateRequest;
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

// Fluxos da conversa depois do termo de canal aceito: cadastro, confirmar/recusar, cancelar, login por código
@ExtendWith(MockitoExtension.class)
class BotOrquestradorFluxosTest {

    private static final String NUMERO = "5511900000003";
    private static final CanalService.Canal CLINICA_A = new CanalService.Canal("sgsm-a", "clinica-a", "Clinica A");

    @Mock private SessaoService sessaoService;
    @Mock private SgsmIaClient sgsmIaClient;
    @Mock private EvolutionApiClient evolutionApiClient;
    @Mock private ExecutorAcaoService executorAcaoService;
    @Mock private AuthClient authClient;
    @Mock private SgsmClient sgsmClient;
    @Mock private SistemaAuthService sistemaAuthService;
    @Mock private BotProperties props;
    @Mock private BotProperties.ConsentimentoCadastro consentimentoCadastro;

    private BotOrquestradorService orquestrador;
    private SessaoBot sessao;
    private ClassificarResponse resp;

    @BeforeEach
    void setUp() {
        orquestrador = new BotOrquestradorService(sessaoService, sgsmIaClient, evolutionApiClient,
                executorAcaoService, authClient, sgsmClient, props, sistemaAuthService, new ObjectMapper());
        // termo do canal já aceito: a conversa vai direto para os fluxos
        sessao = SessaoBot.builder().numero(NUMERO).historico(new ArrayList<>()).consentimentoAceito(true).build();
        lenient().when(sessaoService.obter(NUMERO, "sgsm-a")).thenReturn(sessao);
        lenient().when(sistemaAuthService.token()).thenReturn("jwt-bot");
        lenient().when(props.consentimentoCadastro()).thenReturn(consentimentoCadastro);
        lenient().when(consentimentoCadastro.mensagem()).thenReturn("Termo do cadastro — responda ACEITO");
        resp = new ClassificarResponse();
        resp.setDadosFaltantes(List.of());
        resp.setEntidades(new EntidadesExtraidas());
    }

    private void classificarComo(IntentWhatsApp intent, String resposta) {
        resp.setIntent(intent);
        resp.setRespostaUsuario(resposta);
        when(sgsmIaClient.classificar(any(), any())).thenReturn(resp);
    }

    private void autenticado() {
        sessao.setAccessToken("jwt-paciente");
        sessao.setPerfil("PACIENTE");
        sessao.setUsuarioId("pac-1");
    }

    private void falar(String texto) {
        orquestrador.processar(NUMERO, texto, "Maria", CLINICA_A);
    }

    private String ultimaResposta() {
        var texto = ArgumentCaptor.forClass(String.class);
        verify(evolutionApiClient, atLeastOnce()).enviarTexto(eq("sgsm-a"), eq(NUMERO), texto.capture());
        return texto.getValue();
    }

    // ── termo do cadastro (LGPD 3.1) ─────────────────────────────────────────

    @Test
    void termoDoCadastroSoAvancaComAceito() {
        sessao.setConfirmacaoPendente(new ConfirmacaoPendente("CONSENTIMENTO_CADASTRO", Map.of()));

        falar("talvez");
        assertThat(ultimaResposta()).isEqualTo("Termo do cadastro — responda ACEITO");
        assertThat(sessao.isConsentimentoCadastroAceito()).isFalse();

        falar("aceito");
        assertThat(sessao.isConsentimentoCadastroAceito()).isTrue();
        assertThat(sessao.getConfirmacaoPendente()).isNull();
        assertThat(ultimaResposta()).contains("nome completo, CPF");
        verifyNoInteractions(sgsmIaClient);
    }

    @Test
    void pedidoDeCadastroSemTermoPedeOTermoEComTermoJuntaOsDados() {
        classificarComo(IntentWhatsApp.CADASTRAR, "Me diga seus dados");

        falar("quero me cadastrar");
        assertThat(sessao.getConfirmacaoPendente().getTipo()).isEqualTo("CONSENTIMENTO_CADASTRO");
        assertThat(ultimaResposta()).isEqualTo("Termo do cadastro — responda ACEITO");

        sessao.setConfirmacaoPendente(null);
        sessao.setConsentimentoCadastroAceito(true);
        resp.getEntidades().setNomeCompleto("Maria Silva");
        resp.getEntidades().setCpf("52998224725");
        resp.getEntidades().setDataNascimento("15/04/1991");
        resp.getEntidades().setEmail("m@a.com");
        resp.setRequerConfirmacao(true);
        falar("Maria Silva, 52998224725, 15/04/1991, m@a.com");

        assertThat(sessao.getConfirmacaoPendente().getTipo()).isEqualTo("CADASTRAR");
        assertThat(sessao.getConfirmacaoPendente().getPayload()).containsEntry("cpf", "52998224725")
                .containsEntry("email", "m@a.com");
    }

    @Test
    void confirmarOCadastroCriaPacienteVinculaAClinicaEPedeOCodigo() {
        classificarComo(IntentWhatsApp.CONFIRMAR, null);
        sessao.setConsentimentoCadastroAceito(true);
        sessao.setConfirmacaoPendente(new ConfirmacaoPendente("CADASTRAR", new HashMap<>(Map.of(
                "nomeCompleto", "Maria Silva", "cpf", "52998224725", "dataNascimento", "15/04/1991", "email", "m@a.com"))));
        // reinicia a sessão com a clínica, como no webhook
        sessao.setInstancia("sgsm-a");
        sessao.setEstabelecimentoId("clinica-a");
        when(sgsmClient.criarPaciente(any(), eq("jwt-bot"))).thenReturn(Map.of("id", "pac-9"));
        doThrow(new ResourceAccessException("sgsm fora")).when(sgsmClient)
                .vincularPaciente("pac-9", "clinica-a", NUMERO, "jwt-bot");

        falar("sim");

        var paciente = ArgumentCaptor.forClass(PacienteCreateRequest.class);
        verify(sgsmClient).criarPaciente(paciente.capture(), eq("jwt-bot"));
        assertThat(paciente.getValue().getDataNascimento()).isEqualTo("1991-04-15");
        assertThat(paciente.getValue().getConsentimentoLgpd()).isTrue();
        var registro = ArgumentCaptor.forClass(AuthRegistrarRequest.class);
        verify(authClient).registrar(registro.capture());
        assertThat(registro.getValue().referenciaId()).isEqualTo("pac-9");
        verify(executorAcaoService).enviarOtp("sgsm-a", NUMERO, "m@a.com");
        assertThat(sessao.getConfirmacaoPendente().getTipo()).isEqualTo("OTP");
        assertThat(ultimaResposta()).startsWith("Cadastro criado!");
    }

    @Test
    void falhaNoCadastroAvisaOPaciente() {
        classificarComo(IntentWhatsApp.CONFIRMAR, null);
        sessao.setConfirmacaoPendente(new ConfirmacaoPendente("CADASTRAR", new HashMap<>(Map.of("email", "m@a.com"))));
        when(sgsmClient.criarPaciente(any(), any())).thenThrow(new ResourceAccessException("400"));

        falar("sim");

        assertThat(ultimaResposta()).startsWith("Erro ao realizar cadastro");
        assertThat(sessao.getConfirmacaoPendente()).isNull();
    }

    // ── login por código (OTP) ───────────────────────────────────────────────

    @Test
    void codigoCertoAutenticaVinculaAClinicaEConverteOLead() {
        sessao.setInstancia("sgsm-a");
        sessao.setEstabelecimentoId("clinica-a");
        sessao.setConfirmacaoPendente(new ConfirmacaoPendente("OTP", Map.of("email", "m@a.com")));
        when(authClient.verificarOtp("m@a.com", "123456")).thenReturn(
                Map.of("accessToken", "jwt-novo", "refreshToken", "r", "tipoPerfil", "PACIENTE"));
        when(authClient.me("jwt-novo")).thenReturn(Map.of("referenciaId", "pac-1"));
        when(sessaoService.leadDe("clinica-a", NUMERO)).thenReturn("lead-1");
        doThrow(new ResourceAccessException("ia fora")).when(sgsmIaClient).converterLead("lead-1");

        falar(" 123456 ");

        assertThat(sessao.getAccessToken()).isEqualTo("jwt-novo");
        assertThat(sessao.getUsuarioId()).isEqualTo("pac-1");
        assertThat(sessao.getConfirmacaoPendente()).isNull();
        verify(sgsmClient).vincularPaciente("pac-1", "clinica-a", NUMERO, "jwt-bot");
        verify(sessaoService, never()).guardarLead(any(), any(), eq(BotOrquestradorService.LEAD_CONVERTIDO));
        assertThat(ultimaResposta()).startsWith("Conta ativada com sucesso!").contains("Ver meus agendamentos");
    }

    @Test
    void codigoDeFuncionarioNaoViraVinculoEErroNoMeNaoBarraOLogin() {
        sessao.setConfirmacaoPendente(new ConfirmacaoPendente("OTP", Map.of("email", "f@a.com")));
        when(authClient.verificarOtp("f@a.com", "654321")).thenReturn(
                Map.of("accessToken", "jwt-f", "refreshToken", "r", "tipoPerfil", "FUNCIONARIO"));
        when(authClient.me("jwt-f")).thenThrow(new ResourceAccessException("auth fora"));

        falar("654321");

        assertThat(sessao.getUsuarioId()).isNull();
        verify(sgsmClient, never()).vincularPaciente(any(), any(), any(), any());
        assertThat(ultimaResposta()).startsWith("Conta ativada");
    }

    @Test
    void codigoErradoPedeDeNovo() {
        sessao.setConfirmacaoPendente(new ConfirmacaoPendente("OTP", Map.of("email", "m@a.com")));
        when(authClient.verificarOtp(any(), any())).thenThrow(new ResourceAccessException("401"));

        falar("000000");

        assertThat(ultimaResposta()).isEqualTo("Código incorreto ou expirado. Tente novamente.");
        assertThat(sessao.getConfirmacaoPendente().getTipo()).isEqualTo("OTP");
    }

    // ── intenções ────────────────────────────────────────────────────────────

    @Test
    void semRespostaDaIaAvisaIndisponibilidade() {
        when(sgsmIaClient.classificar(any(), any())).thenReturn(null);

        falar("oi");

        assertThat(ultimaResposta()).startsWith("Serviço temporariamente indisponível");
        verify(sessaoService, never()).salvar(any());
    }

    @Test
    void autenticarJaLogadoOuSemEmail() {
        classificarComo(IntentWhatsApp.AUTENTICAR, "Vamos confirmar quem é você.");

        falar("quero entrar");
        assertThat(ultimaResposta()).contains("Digite seu e-mail cadastrado");

        autenticado();
        falar("quero entrar");
        assertThat(ultimaResposta()).isEqualTo("Você já está autenticado como PACIENTE.");
    }

    @Test
    void cancelarExigeLoginEGuardaOAgendamentoParaConfirmar() {
        classificarComo(IntentWhatsApp.CANCELAR, "Confirma o cancelamento?");

        falar("cancelar minha consulta");
        assertThat(ultimaResposta()).startsWith("Para cancelar, primeiro preciso confirmar sua identidade.");

        autenticado();
        resp.getEntidades().setAgendamentoId("ag-1");
        falar("cancelar minha consulta");
        assertThat(sessao.getConfirmacaoPendente().getTipo()).isEqualTo("CANCELAR");
        assertThat(ultimaResposta()).isEqualTo("Confirma o cancelamento?");
    }

    @Test
    void confirmarCancelamentoExecutaESemPendenciaAvisa() {
        classificarComo(IntentWhatsApp.CONFIRMAR, null);
        autenticado();

        falar("sim");
        assertThat(ultimaResposta()).isEqualTo("Não há nenhuma ação pendente para confirmar.");

        sessao.setConfirmacaoPendente(new ConfirmacaoPendente("CANCELAR", Map.of("agendamentoId", "ag-1")));
        when(executorAcaoService.executarCancelamento(sessao, "ag-1")).thenReturn("Agendamento cancelado.");
        falar("sim");
        assertThat(ultimaResposta()).isEqualTo("Agendamento cancelado.");
        assertThat(sessao.getConfirmacaoPendente()).isNull();

        sessao.setConfirmacaoPendente(new ConfirmacaoPendente("OUTRA", Map.of()));
        falar("sim");
        assertThat(ultimaResposta()).isEqualTo("Ação desconhecida.");
    }

    @Test
    void recusarLiberaAReservaEVoltaAoMenu() {
        classificarComo(IntentWhatsApp.RECUSAR, null);
        autenticado();
        sessao.setConfirmacaoPendente(new ConfirmacaoPendente("AGENDAR",
                new HashMap<>(Map.of(ExecutorAcaoService.CHAVE_RESERVA, "tok-1"))));

        falar("não");

        verify(executorAcaoService).liberarReserva(sessao, "tok-1");
        assertThat(sessao.getConfirmacaoPendente()).isNull();
        assertThat(ultimaResposta()).startsWith("Tudo bem! Como posso ajudar?");
    }

    @Test
    void ativacaoDeFuncionarioRepassaARespostaDaIa() {
        classificarComo(IntentWhatsApp.ATIVAR_FUNC, "Para ativar, use o link do e-mail.");

        falar("sou funcionário");

        assertThat(ultimaResposta()).isEqualTo("Para ativar, use o link do e-mail.");
    }

    @Test
    void agendarComHorarioOcupadoMostraAsAlternativasEComReservaGuardaOToken() {
        classificarComo(IntentWhatsApp.AGENDAR, "Confirma consulta dia 04/11 às 10:00?");
        autenticado();
        resp.setRequerConfirmacao(true);
        resp.getEntidades().setServicoMedicoId("serv-1");
        resp.getEntidades().setData("04/11/2026");
        resp.getEntidades().setHora("10:00");
        when(executorAcaoService.reservar(eq(sessao), any()))
                .thenReturn(new ExecutorAcaoService.ResultadoReserva(
                        ExecutorAcaoService.SituacaoReserva.INDISPONIVEL, null, "Horário ocupado. Opções: 11:00"))
                .thenReturn(new ExecutorAcaoService.ResultadoReserva(
                        ExecutorAcaoService.SituacaoReserva.RESERVADO, "tok-2", null));

        falar("quero agendar dia 04/11 às 10:00");
        assertThat(ultimaResposta()).isEqualTo("Horário ocupado. Opções: 11:00");
        assertThat(sessao.getConfirmacaoPendente()).isNull();

        falar("quero agendar dia 04/11 às 10:00");
        assertThat(sessao.getConfirmacaoPendente().getTipo()).isEqualTo("AGENDAR");
        assertThat(sessao.getConfirmacaoPendente().getPayload())
                .containsEntry(ExecutorAcaoService.CHAVE_RESERVA, "tok-2")
                .containsEntry("estabelecimentoId", "clinica-a");
    }

    @Test
    void agendarConfirmadoSemServicoDataOuHoraPedeOQueFalta() {
        classificarComo(IntentWhatsApp.AGENDAR, "Confirma?");
        autenticado();
        resp.setRequerConfirmacao(true);
        resp.setEntidades(null);

        falar("agendar");

        assertThat(ultimaResposta()).startsWith("Para agendar, preciso saber o serviço");
        verify(executorAcaoService, never()).reservar(any(), any());
    }
}
