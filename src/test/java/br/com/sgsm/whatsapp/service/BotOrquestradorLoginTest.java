package br.com.sgsm.whatsapp.service;

import br.com.sgsm.whatsapp.client.AuthClient;
import br.com.sgsm.whatsapp.client.EvolutionApiClient;
import br.com.sgsm.whatsapp.client.SgsmClient;
import br.com.sgsm.whatsapp.client.SgsmIaClient;
import br.com.sgsm.whatsapp.domain.IntentWhatsApp;
import br.com.sgsm.whatsapp.domain.SessaoBot;
import br.com.sgsm.whatsapp.dto.ClassificarResponse;
import br.com.sgsm.whatsapp.dto.EntidadesExtraidas;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// Rodada 8 do QA: paciente com conta (site/clínica) não conseguia entrar pelo WhatsApp
@ExtendWith(MockitoExtension.class)
class BotOrquestradorLoginTest {

    private static final String NUMERO = "5511900000001";
    private static final String EMAIL = "maria@teste.com";

    @Mock private SessaoService sessaoService;
    @Mock private SgsmIaClient sgsmIaClient;
    @Mock private EvolutionApiClient evolutionApiClient;
    @Mock private ExecutorAcaoService executorAcaoService;
    @Mock private AuthClient authClient;
    @Mock private SgsmClient sgsmClient;
    @Mock private SistemaAuthService sistemaAuthService;

    private BotOrquestradorService orquestrador;
    private SessaoBot sessao;

    @BeforeEach
    void setUp() {
        orquestrador = new BotOrquestradorService(sessaoService, sgsmIaClient, evolutionApiClient,
                executorAcaoService, authClient, sgsmClient, null, sistemaAuthService, new ObjectMapper());
        sessao = SessaoBot.builder().numero(NUMERO).historico(new ArrayList<>()).consentimentoAceito(true).build();
        when(sessaoService.obter(NUMERO, null)).thenReturn(sessao);
        lenient().when(sistemaAuthService.token()).thenReturn("jwt-bot");
    }

    @Test
    void emailDigitadoPeloPacienteSemLoginPedeOCodigoPorEmailSemPassarPelaIa() {
        orquestrador.processar(NUMERO, " " + EMAIL + " ", null);

        verify(authClient).enviarOtpPorEmail(EMAIL, "jwt-bot");
        verifyNoInteractions(sgsmIaClient, executorAcaoService);
        assertThat(sessao.getConfirmacaoPendente().getTipo()).isEqualTo("OTP");
        assertThat(sessao.getConfirmacaoPendente().getPayload()).containsEntry("email", EMAIL);
        // o código vai para o e-mail, nunca para este WhatsApp
        verify(evolutionApiClient).enviarTexto(eq(NUMERO), contains("enviei para ele um código"));
        verify(evolutionApiClient, never()).enviarTexto(eq(NUMERO), matches("(?s).*\\d{6}.*"));
    }

    @Test
    void intencaoAutenticarComEmailExtraidoTambemIniciaOLogin() {
        var resp = new ClassificarResponse();
        resp.setIntent(IntentWhatsApp.AUTENTICAR);
        resp.setRespostaUsuario("Vou te identificar.");
        resp.setDadosFaltantes(List.of());
        var ent = new EntidadesExtraidas();
        ent.setEmail(EMAIL);
        resp.setEntidades(ent);
        when(sgsmIaClient.classificar(any(), any())).thenReturn(resp);

        orquestrador.processar(NUMERO, "meu email é " + EMAIL, null);

        verify(authClient).enviarOtpPorEmail(EMAIL, "jwt-bot");
        assertThat(sessao.getConfirmacaoPendente().getTipo()).isEqualTo("OTP");
    }

    @Test
    void falhaAoPedirOCodigoNaoDeixaAConversaPresa() {
        doThrow(new ResourceAccessException("auth fora")).when(authClient).enviarOtpPorEmail(any(), any());

        orquestrador.processar(NUMERO, EMAIL, null);

        assertThat(sessao.getConfirmacaoPendente()).isNull();
        verify(evolutionApiClient).enviarTexto(eq(NUMERO), contains("Não consegui enviar o código"));
    }

    @Test
    void pacienteJaAutenticadoQueMandaEmailNaoReiniciaOLogin() {
        sessao.setAccessToken("jwt-paciente");
        var resp = new ClassificarResponse();
        resp.setIntent(IntentWhatsApp.CONVERSAR);
        resp.setRespostaUsuario("Ok!");
        resp.setDadosFaltantes(List.of());
        when(sgsmIaClient.classificar(any(), any())).thenReturn(resp);

        orquestrador.processar(NUMERO, EMAIL, null);

        verify(authClient, never()).enviarOtpPorEmail(any(), any());
    }
}
