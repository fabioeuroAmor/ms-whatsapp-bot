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
import com.fasterxml.jackson.core.JsonProcessingException;
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

// Ramos de borda do orquestrador: falhas dos serviços, canal geral, escolha de clínica e respostas vazias da IA
@ExtendWith(MockitoExtension.class)
class BotOrquestradorRamosTest {

    private static final String NUMERO = "5511900000004";
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
    @Mock private BotProperties.ConsentimentoCadastro consentimentoCadastro;

    private BotOrquestradorService orquestrador;
    private SessaoBot sessao;
    private ClassificarResponse resp;

    @BeforeEach
    void setUp() {
        orquestrador = novoOrquestrador(new ObjectMapper());
        sessao = SessaoBot.builder().numero(NUMERO).historico(new ArrayList<>()).consentimentoAceito(true).build();
        lenient().when(sessaoService.obter(eq(NUMERO), any())).thenReturn(sessao);
        lenient().when(sistemaAuthService.token()).thenReturn("jwt-bot");
        lenient().when(props.consentimento()).thenReturn(consentimento);
        lenient().when(consentimento.mensagem()).thenReturn("Termo LGPD — responda ACEITO");
        lenient().when(props.consentimentoCadastro()).thenReturn(consentimentoCadastro);
        lenient().when(consentimentoCadastro.mensagem()).thenReturn("Termo do cadastro");
        resp = new ClassificarResponse();
        resp.setDadosFaltantes(List.of());
        resp.setEntidades(new EntidadesExtraidas());
    }

    private BotOrquestradorService novoOrquestrador(ObjectMapper mapper) {
        return new BotOrquestradorService(sessaoService, sgsmIaClient, evolutionApiClient,
                executorAcaoService, authClient, sgsmClient, props, sistemaAuthService, mapper);
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

    private String ultimaRespostaClinica() {
        var texto = ArgumentCaptor.forClass(String.class);
        verify(evolutionApiClient, atLeastOnce()).enviarTexto(eq("sgsm-a"), eq(NUMERO), texto.capture());
        return texto.getValue();
    }

    private String ultimaRespostaGeral() {
        var texto = ArgumentCaptor.forClass(String.class);
        verify(evolutionApiClient, atLeastOnce()).enviarTexto(eq(NUMERO), texto.capture());
        return texto.getValue();
    }

    // ── termo do canal ───────────────────────────────────────────────────────

    @Test
    void falhaAoConsultarOuRegistrarConsentimentoECriarLeadNaoTravaOAtendimento() {
        sessao.setConsentimentoAceito(false);
        sessao.setNomeUsuario("Já Conhecida");
        when(sgsmClient.consentimentoVigente(any(), any(), any())).thenThrow(new ResourceAccessException("sgsm fora"));
        doThrow(new ResourceAccessException("sgsm fora")).when(sgsmClient).registrarConsentimento(any(), any(), any(), any());
        when(sessaoService.leadDe("clinica-a", NUMERO)).thenReturn(null);
        when(sgsmIaClient.criarLead(any(), any(), any())).thenThrow(new ResourceAccessException("ia fora"));

        orquestrador.processar(NUMERO, "oi", "Outro Nome", CLINICA_A);
        assertThat(ultimaRespostaClinica()).isEqualTo("Termo LGPD — responda ACEITO");

        orquestrador.processar(NUMERO, "ACEITO", null, CLINICA_A);
        assertThat(sessao.isConsentimentoAceito()).isTrue();
        assertThat(sessao.getNomeUsuario()).isEqualTo("Já Conhecida");
        assertThat(ultimaRespostaClinica()).startsWith("Como posso ajudar?");
        verify(sessaoService, never()).guardarLead(any(), any(), any());
    }

    @Test
    void leadSemIdNaoEGuardadoELeadJaExistenteNaoDuplica() {
        sessao.setConsentimentoAceito(false);
        when(sgsmClient.consentimentoVigente(any(), any(), any())).thenReturn(false);
        when(sessaoService.leadDe("clinica-a", NUMERO)).thenReturn(null, "lead-antigo");
        when(sgsmIaClient.criarLead("Contato WhatsApp", NUMERO, "clinica-a")).thenReturn(null);

        orquestrador.processar(NUMERO, "aceito", null, CLINICA_A);
        sessao.setConsentimentoAceito(false);
        orquestrador.processar(NUMERO, "aceito", null, CLINICA_A);

        verify(sgsmIaClient, times(1)).criarLead(any(), any(), any());
        verify(sessaoService, never()).guardarLead(any(), any(), any());
    }

    @Test
    void aceiteNoCanalGeralNaoRegistraNaClinicaNemCriaLead() {
        sessao.setConsentimentoAceito(false);

        orquestrador.processar(NUMERO, "ACEITO", "Ana", GERAL);

        assertThat(ultimaRespostaGeral()).startsWith("Como posso ajudar?");
        verifyNoInteractions(sgsmClient);
        verify(sgsmIaClient, never()).criarLead(any(), any(), any());
    }

    // ── canal geral: escolha da clínica ──────────────────────────────────────

    @Test
    void agendarNoCanalGeralListaClinicasEAceitaSoNumeroValido() {
        classificarComo(IntentWhatsApp.AGENDAR, "Vamos agendar");
        autenticado();
        when(sgsmClient.listarEstabelecimentosAtivos("jwt-bot")).thenReturn(List.of(
                Map.of("id", "clinica-a", "nome", "Clinica A"), Map.of("id", "clinica-b", "nome", "Clinica B")));

        orquestrador.processar(NUMERO, "quero agendar", null, GERAL);
        assertThat(ultimaRespostaGeral()).contains("*1.* Clinica A").contains("*2.* Clinica B");
        assertThat(sessao.getConfirmacaoPendente().getTipo()).isEqualTo("ESCOLHER_CLINICA");

        orquestrador.processar(NUMERO, "a terceira", null, GERAL);
        assertThat(ultimaRespostaGeral()).startsWith("Não entendi.");
        orquestrador.processar(NUMERO, "9", null, GERAL);
        assertThat(ultimaRespostaGeral()).startsWith("Não entendi.");

        orquestrador.processar(NUMERO, "opção 2", null, GERAL);
        assertThat(sessao.getEstabelecimentoId()).isEqualTo("clinica-b");
        assertThat(sessao.getConfirmacaoPendente()).isNull();
        assertThat(ultimaRespostaGeral()).contains("*Clinica B*");
    }

    @Test
    void semClinicasOuComFalhaAoListarAvisaOPaciente() {
        classificarComo(IntentWhatsApp.AGENDAR, "Vamos agendar");
        autenticado();
        when(sgsmClient.listarEstabelecimentosAtivos("jwt-bot"))
                .thenReturn(null)
                .thenReturn(List.of())
                .thenThrow(new ResourceAccessException("sgsm fora"));

        orquestrador.processar(NUMERO, "agendar", null, GERAL);
        assertThat(ultimaRespostaGeral()).startsWith("No momento não há clínicas");
        orquestrador.processar(NUMERO, "agendar", null, GERAL);
        assertThat(ultimaRespostaGeral()).startsWith("No momento não há clínicas");
        orquestrador.processar(NUMERO, "agendar", null, GERAL);
        assertThat(ultimaRespostaGeral()).startsWith("Não consegui carregar as clínicas");
    }

    @Test
    void escolhaDeClinicaSemOpcoesSalvasPedeDeNovo() {
        sessao.setConfirmacaoPendente(new ConfirmacaoPendente("ESCOLHER_CLINICA", new HashMap<>()));

        orquestrador.processar(NUMERO, "1", null, GERAL);

        assertThat(ultimaRespostaGeral()).startsWith("Não entendi.");
    }

    // ── login e intenções ────────────────────────────────────────────────────

    @Test
    void emailDigitadoComCadastroEmAndamentoVaiParaAIa() {
        sessao.getDadosCadastro().put("nomeCompleto", "Maria");
        classificarComo(IntentWhatsApp.CADASTRAR, "Agora o CPF");
        sessao.setConsentimentoCadastroAceito(true);
        resp.setEntidades(null);

        orquestrador.processar(NUMERO, "maria@a.com", null, CLINICA_A);

        verify(authClient, never()).enviarOtpPorEmail(any(), any());
        assertThat(ultimaRespostaClinica()).isEqualTo("Agora o CPF");
    }

    @Test
    void autenticarComEmailExtraidoPelaIaPedeOCodigoEFalhaNoAuthAvisa() {
        classificarComo(IntentWhatsApp.AUTENTICAR, "Vou te identificar");
        resp.getEntidades().setEmail(" m@a.com ");
        doNothing().doThrow(new ResourceAccessException("auth fora")).when(authClient).enviarOtpPorEmail("m@a.com", "jwt-bot");

        orquestrador.processar(NUMERO, "meu email é m@a.com", null, CLINICA_A);
        assertThat(sessao.getConfirmacaoPendente().getTipo()).isEqualTo("OTP");
        assertThat(ultimaRespostaClinica()).contains("enviei para ele um código");

        sessao.setConfirmacaoPendente(null);
        orquestrador.processar(NUMERO, "meu email é m@a.com", null, CLINICA_A);
        assertThat(ultimaRespostaClinica()).startsWith("Não consegui enviar o código");
    }

    @Test
    void emailDiretoComFalhaNoAuthAvisa() {
        doThrow(new ResourceAccessException("auth fora")).when(authClient).enviarOtpPorEmail("m@a.com", "jwt-bot");

        orquestrador.processar(NUMERO, "m@a.com", null, CLINICA_A);

        assertThat(ultimaRespostaClinica()).startsWith("Não consegui enviar o código");
        verifyNoInteractions(sgsmIaClient);
    }

    @Test
    void agendarSemLoginOuComDadosFaltandoRepassaAPergunta() {
        classificarComo(IntentWhatsApp.AGENDAR, "Qual o horário?");

        orquestrador.processar(NUMERO, "agendar", null, CLINICA_A);
        assertThat(ultimaRespostaClinica()).startsWith("Para agendar, primeiro preciso confirmar sua identidade.");

        autenticado();
        resp.setDadosFaltantes(List.of("hora"));
        resp.setRequerConfirmacao(true);
        orquestrador.processar(NUMERO, "agendar", null, CLINICA_A);
        assertThat(ultimaRespostaClinica()).isEqualTo("Qual o horário?");

        resp.setDadosFaltantes(List.of());
        resp.setRequerConfirmacao(false);
        orquestrador.processar(NUMERO, "agendar", null, CLINICA_A);
        assertThat(ultimaRespostaClinica()).isEqualTo("Qual o horário?");
        verify(executorAcaoService, never()).reservar(any(), any());
    }

    @Test
    void agendarSemReservaNoSgsmGuardaOPedidoSemTokenELiberaAReservaAnterior() {
        classificarComo(IntentWhatsApp.AGENDAR, "Confirma?");
        autenticado();
        resp.setRequerConfirmacao(true);
        resp.getEntidades().setServicoMedicoId("serv-1");
        resp.getEntidades().setData("04/11/2026");
        resp.getEntidades().setHora(" ");
        when(executorAcaoService.reservar(eq(sessao), any())).thenReturn(
                new ExecutorAcaoService.ResultadoReserva(ExecutorAcaoService.SituacaoReserva.SEM_RESERVA, null, null));

        orquestrador.processar(NUMERO, "agendar", null, CLINICA_A);
        assertThat(ultimaRespostaClinica()).startsWith("Para agendar, preciso saber o serviço"); // hora em branco

        resp.getEntidades().setHora("10:00");
        sessao.setConfirmacaoPendente(new ConfirmacaoPendente("AGENDAR", new HashMap<>(Map.of("data", "x"))));
        orquestrador.processar(NUMERO, "agendar", null, CLINICA_A);

        verify(executorAcaoService, never()).liberarReserva(any(), any());
        assertThat(sessao.getConfirmacaoPendente().getPayload()).doesNotContainKey(ExecutorAcaoService.CHAVE_RESERVA);
    }

    @Test
    void recusarSemPendenciaOuComPendenciaSemReservaNaoLiberaNada() {
        classificarComo(IntentWhatsApp.RECUSAR, null);

        orquestrador.processar(NUMERO, "não", null, CLINICA_A);
        sessao.setConfirmacaoPendente(new ConfirmacaoPendente("CANCELAR", Map.of("agendamentoId", "ag-1")));
        orquestrador.processar(NUMERO, "não", null, CLINICA_A);
        sessao.setConfirmacaoPendente(new ConfirmacaoPendente("AGENDAR", null));
        orquestrador.processar(NUMERO, "não", null, CLINICA_A);

        verify(executorAcaoService, never()).liberarReserva(any(), any());
        assertThat(ultimaRespostaClinica()).startsWith("Tudo bem!");
    }

    @Test
    void respostaVaziaDaIaViraReticenciasENaoEntraNoHistorico() {
        classificarComo(IntentWhatsApp.CONSULTAR, null);
        sessao.setHistorico(null);

        orquestrador.processar(NUMERO, "?", null, CLINICA_A);

        assertThat(ultimaRespostaClinica()).isEqualTo("...");
        verify(sessaoService).adicionarHistorico(sessao, "USUARIO", "?");
        verify(sessaoService, never()).adicionarHistorico(eq(sessao), eq("ASSISTENTE"), any());
    }

    @Test
    void pendenciaVaiSerializadaParaAIaEFalhaNaSerializacaoNaoQuebra() throws Exception {
        classificarComo(IntentWhatsApp.CONVERSAR, "Ok");
        sessao.setConfirmacaoPendente(new ConfirmacaoPendente("CANCELAR", Map.of("agendamentoId", "ag-1")));

        orquestrador.processar(NUMERO, "e aí?", null, CLINICA_A);
        var pedido = ArgumentCaptor.forClass(ClassificarRequest.class);
        verify(sgsmIaClient).classificar(pedido.capture(), any());
        assertThat(pedido.getValue().confirmacaoPendente()).contains("CANCELAR");

        var mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("falhou") {});
        novoOrquestrador(mapper).processar(NUMERO, "e aí?", null, CLINICA_A);
        verify(sgsmIaClient, times(2)).classificar(pedido.capture(), any());
        assertThat(pedido.getValue().confirmacaoPendente()).isNull();
    }

    @Test
    void loginSemReferenciaNoMeNaoVinculaNemConverteLeadJaConvertido() {
        sessao.setInstancia("sgsm-a");
        sessao.setEstabelecimentoId("clinica-a");
        sessao.setConfirmacaoPendente(new ConfirmacaoPendente("OTP", Map.of("email", "m@a.com")));
        when(authClient.verificarOtp("m@a.com", "111111")).thenReturn(
                Map.of("accessToken", "jwt", "refreshToken", "r", "tipoPerfil", "PACIENTE"));
        when(authClient.me("jwt")).thenReturn(Map.of());

        orquestrador.processar(NUMERO, "111111", null, CLINICA_A);

        assertThat(sessao.getUsuarioId()).isNull();
        verify(sgsmClient, never()).vincularPaciente(any(), any(), any(), any());

        sessao.setUsuarioId(null);
        sessao.setConfirmacaoPendente(new ConfirmacaoPendente("OTP", Map.of("email", "m@a.com")));
        when(authClient.me("jwt")).thenReturn(Map.of("referenciaId", "pac-1"));
        when(sessaoService.leadDe("clinica-a", NUMERO)).thenReturn(BotOrquestradorService.LEAD_CONVERTIDO);
        orquestrador.processar(NUMERO, "111111", null, CLINICA_A);

        verify(sgsmClient).vincularPaciente("pac-1", "clinica-a", NUMERO, "jwt-bot");
        verify(sgsmIaClient, never()).converterLead(any());
    }

    @Test
    void cadastroSoComParteDosDadosGuardaOQueVeio() {
        classificarComo(IntentWhatsApp.CADASTRAR, "Falta o e-mail");
        sessao.setConsentimentoCadastroAceito(true);
        sessao.setDadosCadastro(null);
        resp.getEntidades().setCpf("52998224725");

        orquestrador.processar(NUMERO, "meu cpf é 52998224725", null, CLINICA_A);

        assertThat(sessao.getDadosCadastro()).containsOnlyKeys("cpf");
        assertThat(sessao.getConfirmacaoPendente()).isNull();
    }
}
