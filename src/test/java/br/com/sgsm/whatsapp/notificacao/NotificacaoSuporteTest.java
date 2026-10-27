package br.com.sgsm.whatsapp.notificacao;

import br.com.sgsm.whatsapp.controller.NotificacaoInternalController;
import br.com.sgsm.whatsapp.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// Disparo da agenda diária (cron e manual em dev), envio em lote e respostas de erro da API do bot
class NotificacaoSuporteTest {

    @Test
    void cronEDisparoManualChamamAAgendaDiaria() {
        var agenda = mock(AgendaDiariaService.class);

        new AgendaDiariaScheduler(agenda).executar();
        var resposta = new NotificacaoInternalController(agenda).executarAgendaDiaria();

        assertThat(resposta.getStatusCode().value()).isEqualTo(202);
        verify(agenda, times(2)).enviarAgendaDoDia();
    }

    @Test
    void falhaDeUmDestinatarioNaoInterrompeOsDemais() {
        var enviados = new ArrayList<String>();

        new EnvioEmLoteHelper().executarComEspacamento(List.of("ana", "erro", "bia"), nome -> {
            if (nome.equals("erro")) throw new IllegalStateException("evolution fora");
            enviados.add(nome);
        }, nome -> nome);

        assertThat(enviados).containsExactly("ana", "bia");
    }

    @Test
    void threadInterrompidaParaOLoteDepoisDoItemAtual() {
        var enviados = new ArrayList<String>();
        Thread.currentThread().interrupt();
        try {
            new EnvioEmLoteHelper().executarComEspacamento(List.of("ana", "bia"), enviados::add, nome -> nome);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(enviados).containsExactly("ana");
    }

    @Test
    void errosDaApiViram400Ou500ComMensagem() {
        var handler = new GlobalExceptionHandler();

        assertThat(handler.handleIllegalArgument(new IllegalArgumentException("número inválido")))
                .containsEntry("status", 400).containsEntry("erro", "número inválido");
        assertThat(handler.handleGeneric(new RuntimeException("falhou"))).containsEntry("status", 500)
                .containsEntry("erro", "falhou");
        assertThat(handler.handleGeneric(new RuntimeException())).containsEntry("erro", "Erro interno");
    }
}
