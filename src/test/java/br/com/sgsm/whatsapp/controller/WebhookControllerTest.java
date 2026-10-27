package br.com.sgsm.whatsapp.controller;

import br.com.sgsm.whatsapp.config.BotProperties;
import br.com.sgsm.whatsapp.dto.EvolutionWebhookPayload;
import br.com.sgsm.whatsapp.service.BotOrquestradorService;
import br.com.sgsm.whatsapp.service.CanalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// Webhook da Evolution: segredo obrigatório, só mensagem individual de texto recebida, clínica pela instância
@ExtendWith(MockitoExtension.class)
class WebhookControllerTest {

    private static final String SEGREDO = "segredo-teste";

    @Mock private BotOrquestradorService orquestrador;
    @Mock private BotProperties props;
    @Mock private BotProperties.Evolution evolution;
    @Mock private CanalService canalService;

    private WebhookController controller;
    private final CanalService.Canal clinicaA = new CanalService.Canal("sgsm-a", "clinica-a", "Clinica A");

    @BeforeEach
    void setUp() {
        lenient().when(props.evolution()).thenReturn(evolution);
        lenient().when(evolution.webhookSecret()).thenReturn(SEGREDO);
        controller = new WebhookController(orquestrador, props, canalService);
    }

    private static EvolutionWebhookPayload mensagem(String instancia, String jid, Boolean fromMe, String texto, String textoEstendido) {
        var key = new EvolutionWebhookPayload.EvolutionKey();
        key.setRemoteJid(jid);
        key.setFromMe(fromMe);
        var msg = new EvolutionWebhookPayload.EvolutionMessage();
        msg.setConversation(texto);
        if (textoEstendido != null) {
            var ext = new EvolutionWebhookPayload.EvolutionExtendedText();
            ext.setText(textoEstendido);
            msg.setExtendedTextMessage(ext);
        }
        var data = new EvolutionWebhookPayload.EvolutionData();
        data.setKey(key);
        data.setMessage(msg);
        data.setPushName("Ana");
        var p = new EvolutionWebhookPayload();
        p.setEvent("messages.upsert");
        p.setInstance(instancia);
        p.setData(data);
        return p;
    }

    @Test
    void semSegredoOuComSegredoErradoDa401ENaoProcessa() {
        var p = mensagem("sgsm-a", "5561@s.whatsapp.net", false, "oi", null);

        assertThat(controller.receberEvento(p, null).getStatusCode().value()).isEqualTo(401);
        assertThat(controller.receberEvento(p, "outro").getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(orquestrador, canalService);
    }

    @Test
    void mensagemDaClinicaEProcessadaComOCanalDaInstancia() {
        when(canalService.resolver("sgsm-a")).thenReturn(Optional.of(clinicaA));

        var resposta = controller.receberEvento(mensagem("sgsm-a", "5561@s.whatsapp.net", false, "oi", null), SEGREDO);

        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
        verify(orquestrador, timeout(2000)).processar("5561", "oi", "Ana", clinicaA);
    }

    @Test
    void textoComFormatacaoTambemEProcessado() {
        when(canalService.resolver("sgsm-a")).thenReturn(Optional.of(clinicaA));

        controller.receberEvento(mensagem("sgsm-a", "5561@s.whatsapp.net", null, null, "quero agendar"), SEGREDO);

        verify(orquestrador, timeout(2000)).processar(eq("5561"), eq("quero agendar"), any(), eq(clinicaA));
    }

    @Test
    void ignoraOQueNaoEMensagemIndividualDeTextoRecebida() {
        var outroEvento = mensagem("sgsm-a", "5561@s.whatsapp.net", false, "oi", null);
        outroEvento.setEvent("connection.update");
        var semDados = mensagem("sgsm-a", "5561@s.whatsapp.net", false, "oi", null);
        semDados.setData(null);
        var semChave = mensagem("sgsm-a", "5561@s.whatsapp.net", false, "oi", null);
        semChave.getData().setKey(null);
        var semMensagem = mensagem("sgsm-a", "5561@s.whatsapp.net", false, "oi", null);
        semMensagem.getData().setMessage(null);

        for (var p : new EvolutionWebhookPayload[] {
                outroEvento, semDados, semChave, semMensagem,
                mensagem("sgsm-a", "5561@s.whatsapp.net", true, "enviada pelo bot", null),
                mensagem("sgsm-a", "123-456@g.us", false, "grupo", null),
                mensagem("sgsm-a", null, false, "sem jid", null),
                mensagem("sgsm-a", "5561@s.whatsapp.net", false, "   ", null)}) {
            assertThat(controller.receberEvento(p, SEGREDO).getStatusCode().value()).isEqualTo(200);
        }
        verifyNoInteractions(orquestrador, canalService);
    }

    @Test
    void instanciaDesconhecidaOuErroNaResolucaoRespondem200SemProcessar() {
        when(canalService.resolver("sgsm-x")).thenReturn(Optional.empty());
        when(canalService.resolver("sgsm-erro")).thenThrow(new IllegalStateException("falhou"));

        assertThat(controller.receberEvento(mensagem("sgsm-x", "5561@s.whatsapp.net", false, "oi", null), SEGREDO)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(controller.receberEvento(mensagem("sgsm-erro", "5561@s.whatsapp.net", false, "oi", null), SEGREDO)
                .getStatusCode().value()).isEqualTo(200);
        verify(orquestrador, after(300).never()).processar(anyString(), anyString(), any(), any());
    }
}
