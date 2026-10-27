package br.com.sgsm.whatsapp.client;

import br.com.sgsm.whatsapp.config.BotProperties;
import br.com.sgsm.whatsapp.dto.AgendamentoCreateRequest;
import br.com.sgsm.whatsapp.dto.AuthRegistrarRequest;
import br.com.sgsm.whatsapp.dto.ClassificarRequest;
import br.com.sgsm.whatsapp.dto.PacienteCreateRequest;
import br.com.sgsm.whatsapp.dto.ReservaCreateRequest;
import br.com.sgsm.whatsapp.service.SistemaAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// Contrato HTTP dos clientes do bot (sgsm, ms-sboot-auth, sgsm-ia e Evolution): método, caminho,
// cabeçalhos e corpo de cada chamada, sem subir servidor
class ClientesHttpTest {

    private static final String BASE = "http://servico";
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    private MockRestServiceServer servidor;
    private RestClient restClient;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl(BASE);
        servidor = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
    }

    // ── SgsmClient ──────────────────────────────────────────────────────────

    @Test
    void sgsmClientPacienteEAgendamentos() {
        var sgsm = new SgsmClient(restClient);
        servidor.expect(requestTo(BASE + "/v1/api/pacientes")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tok"))
                .andExpect(jsonPath("$.email").value("p@a.com"))
                .andRespond(withSuccess("{\"id\":\"p1\"}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/v1/api/agendamentos")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":\"a1\"}]", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/v1/api/agendamentos")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.estabelecimentoId").value("clinica-a"))
                .andExpect(jsonPath("$.tipo").value("PRESENCIAL"))
                .andRespond(withSuccess("{\"id\":\"a2\"}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/v1/api/agendamentos/reservas")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.servicoMedicoId").value("s1"))
                .andRespond(withSuccess("{\"token\":\"r1\",\"reservado\":true}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/v1/api/agendamentos/reservas/r1")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        servidor.expect(requestTo(BASE + "/v1/api/agendamentos/a2/cancelar")).andExpect(method(HttpMethod.PATCH))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        var paciente = new PacienteCreateRequest("Ana", "52998224725", "1991-04-15", "p@a.com", "5561", true);
        assertThat(sgsm.criarPaciente(paciente, "tok")).containsEntry("id", "p1");
        assertThat(sgsm.listarAgendamentos("tok")).hasSize(1);
        assertThat(sgsm.criarAgendamento(new AgendamentoCreateRequest("p1", "s1", "2026-11-04T10:00:00-03:00",
                null, "clinica-a", "PRESENCIAL"), "tok")).containsEntry("id", "a2");
        assertThat(sgsm.reservarHorario(new ReservaCreateRequest("s1", "2026-11-04T10:00:00-03:00"), "tok"))
                .containsEntry("token", "r1");
        sgsm.liberarReserva("r1", "tok");
        sgsm.cancelarAgendamento("a2", "tok");
        servidor.verify();
    }

    @Test
    void sgsmClientEndpointsInternosDoWhatsappPorClinica() {
        var sgsm = new SgsmClient(restClient);
        servidor.expect(requestTo(BASE + "/v1/api/interno/whatsapp/canais/sgsm-a")).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer sis"))
                .andRespond(withSuccess("{\"estabelecimentoId\":\"clinica-a\"}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/v1/api/interno/whatsapp/canais")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"instancia\":\"sgsm-a\"}]", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/v1/api/interno/whatsapp/vinculos")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.origem").value("WHATSAPP"))
                .andExpect(jsonPath("$.pacienteId").value("p1"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        servidor.expect(requestTo(BASE + "/v1/api/interno/whatsapp/consentimentos?telefone=5561&estabelecimentoId=clinica-a&canal=WHATSAPP"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"vigente\":true}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/v1/api/interno/whatsapp/consentimentos?telefone=5562&estabelecimentoId=clinica-a&canal=WHATSAPP"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/v1/api/interno/whatsapp/consentimentos")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.versaoTexto").value("whatsapp-lgpd-v1"))
                .andExpect(jsonPath("$.canal").value("WHATSAPP"))
                .andRespond(withStatus(HttpStatus.CREATED));

        assertThat(sgsm.resolverCanal("sgsm-a", "sis")).containsEntry("estabelecimentoId", "clinica-a");
        assertThat(sgsm.listarCanaisConectados("sis")).hasSize(1);
        sgsm.vincularPaciente("p1", "clinica-a", "5561", "sis");
        assertThat(sgsm.consentimentoVigente("5561", "clinica-a", "sis")).isTrue();
        assertThat(sgsm.consentimentoVigente("5562", "clinica-a", "sis")).isFalse();
        sgsm.registrarConsentimento("5561", "clinica-a", "whatsapp-lgpd-v1", "sis");
        servidor.verify();
    }

    @Test
    void sgsmClientConsultasDaAgendaDiariaEDoCanalGeral() {
        var sgsm = new SgsmClient(restClient);
        servidor.expect(requestTo(BASE + "/v1/api/estabelecimentos?ativo=true"))
                .andRespond(withSuccess("[{\"id\":\"clinica-a\"}]", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/v1/api/estabelecimentos/clinica-a/medicos"))
                .andRespond(withSuccess("[{\"id\":\"m1\"}]", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/v1/api/medicos?ativo=true"))
                .andRespond(withSuccess("[{\"id\":\"m1\"},{\"id\":\"m2\"}]", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/v1/api/agendamentos?medicoId=m1&data=2026-10-27"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(sgsm.listarEstabelecimentosAtivos("sis")).hasSize(1);
        assertThat(sgsm.listarMedicosDoEstabelecimento("clinica-a", "sis")).hasSize(1);
        assertThat(sgsm.listarMedicosAtivos("sis")).hasSize(2);
        assertThat(sgsm.listarAgendamentosDoDia("m1", LocalDate.of(2026, 10, 27), "sis")).isEmpty();
        servidor.verify();
    }

    // ── AuthClient ──────────────────────────────────────────────────────────

    @Test
    void authClientLoginOtpRegistroEMe() {
        var auth = new AuthClient(restClient);
        servidor.expect(requestTo(BASE + "/v1/api/auth/login")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.email").value("p@a.com"))
                .andRespond(withSuccess("{\"accessToken\":\"t\"}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/v1/api/auth/otp/gerar")).andExpect(header("Authorization", "Bearer sis"))
                .andRespond(withSuccess("{\"otp\":\"123456\"}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/v1/api/auth/otp/enviar-email")).andExpect(header("Authorization", "Bearer sis"))
                .andExpect(content().json("{\"email\":\"p@a.com\"}"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));
        servidor.expect(requestTo(BASE + "/v1/api/auth/otp/verificar"))
                .andExpect(jsonPath("$.otp").value("123456"))
                .andRespond(withSuccess("{\"valido\":true}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/v1/api/auth/registrar"))
                .andExpect(jsonPath("$.tipoPerfil").value("PACIENTE"))
                .andRespond(withSuccess("{\"id\":\"u1\"}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/v1/api/auth/me")).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer t"))
                .andRespond(withSuccess("{\"perfil\":\"PACIENTE\"}", MediaType.APPLICATION_JSON));

        assertThat(auth.login("p@a.com", "segredo")).containsEntry("accessToken", "t");
        assertThat(auth.gerarOtp("p@a.com", "sis")).containsEntry("otp", "123456");
        auth.enviarOtpPorEmail("p@a.com", "sis");
        assertThat(auth.verificarOtp("p@a.com", "123456")).containsEntry("valido", true);
        assertThat(auth.registrar(new AuthRegistrarRequest("p@a.com", "segredo", "PACIENTE", "p1"))).containsEntry("id", "u1");
        assertThat(auth.me("t")).containsEntry("perfil", "PACIENTE");
        servidor.verify();
    }

    // ── SgsmIaClient ────────────────────────────────────────────────────────

    @Test
    void sgsmIaClientClassificaComTokenDoPacienteOuDeSistemaECuidaDoLead() {
        var sistema = mock(SistemaAuthService.class);
        when(sistema.token()).thenReturn("sis");
        var ia = new SgsmIaClient(restClient, sistema);
        servidor.expect(requestTo(BASE + "/ia/whatsapp/classificar")).andExpect(header("Authorization", "Bearer pac"))
                .andExpect(jsonPath("$.estabelecimentoId").value("clinica-a"))
                .andRespond(withSuccess("{\"intent\":\"CONVERSAR\",\"respostaUsuario\":\"Olá\"}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/ia/whatsapp/classificar")).andExpect(header("Authorization", "Bearer sis"))
                .andRespond(withSuccess("{\"intent\":\"CONVERSAR\"}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/crm/leads")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.origem").value("WHATSAPP"))
                .andExpect(jsonPath("$.estabelecimentoId").value("clinica-a"))
                .andRespond(withSuccess("{\"id\":\"l1\"}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/crm/leads"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/crm/leads/l1/status")).andExpect(method(HttpMethod.PATCH))
                .andExpect(content().json("{\"status\":\"CONVERTIDO\"}"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        var pedido = new ClassificarRequest("oi", List.of(), "PACIENTE", null, "clinica-a", "Clinica A");
        assertThat(ia.classificar(pedido, "pac").getRespostaUsuario()).isEqualTo("Olá");
        assertThat(ia.classificar(new ClassificarRequest("oi", List.of(), null, null), null)).isNotNull();
        assertThat(ia.criarLead("Ana", "5561", "clinica-a")).isEqualTo("l1");
        assertThat(ia.criarLead("Ana", "5561", "clinica-a")).isNull();
        ia.converterLead("l1");
        servidor.verify();
    }

    // ── EvolutionApiClient ──────────────────────────────────────────────────

    @Test
    void evolutionEnviaPeloNumeroDaClinicaOuPeloGeralESemDerrubarOBotQuandoFalha() {
        var props = mock(BotProperties.class);
        var evolution = mock(BotProperties.Evolution.class);
        when(props.evolution()).thenReturn(evolution);
        when(evolution.instance()).thenReturn("sgsm-bot");
        var cliente = new EvolutionApiClient(restClient, props);
        servidor.expect(requestTo(BASE + "/message/sendText/sgsm-a")).andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(JSON))
                .andExpect(jsonPath("$.number").value("5561"))
                .andExpect(jsonPath("$.text").value("Olá"))
                .andRespond(withStatus(HttpStatus.CREATED));
        servidor.expect(requestTo(BASE + "/message/sendText/sgsm-bot"))
                .andRespond(withStatus(HttpStatus.CREATED));
        servidor.expect(requestTo(BASE + "/message/sendText/sgsm-bot"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        cliente.enviarTexto("sgsm-a", "5561", "Olá");
        cliente.enviarTexto("5561", "Olá");
        cliente.enviarTexto("5561", "Olá"); // 500 da Evolution: só loga
        servidor.verify();
    }
}
