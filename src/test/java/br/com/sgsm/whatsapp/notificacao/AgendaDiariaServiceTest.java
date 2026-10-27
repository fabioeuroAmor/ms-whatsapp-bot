package br.com.sgsm.whatsapp.notificacao;

import br.com.sgsm.whatsapp.client.EvolutionApiClient;
import br.com.sgsm.whatsapp.client.SgsmClient;
import br.com.sgsm.whatsapp.service.SistemaAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// DW7: cada médico recebe as consultas de cada clínica pelo WhatsApp dela; o resto, pelo canal geral
@ExtendWith(MockitoExtension.class)
class AgendaDiariaServiceTest {

    @Mock private SgsmClient sgsmClient;
    @Mock private EvolutionApiClient evolutionApiClient;
    @Mock private SistemaAuthService sistemaAuthService;

    private AgendaDiariaService service;

    @BeforeEach
    void setUp() {
        // envio em lote sem espera, na ordem
        var lote = new EnvioEmLoteHelper() {
            @Override
            public <T> void executarComEspacamento(List<T> itens, java.util.function.Consumer<T> acao,
                                                   java.util.function.Function<T, String> id) {
                itens.forEach(acao);
            }
        };
        service = new AgendaDiariaService(sgsmClient, evolutionApiClient, sistemaAuthService, lote);
        when(sistemaAuthService.token()).thenReturn("jwt-bot");
    }

    private static Map<String, Object> consulta(String hora, String clinica, String status) {
        var a = new HashMap<String, Object>();
        a.put("dataHoraInicio", "2026-10-26T" + hora + ":00-03:00");
        a.put("pacienteNome", "Paciente " + hora);
        a.put("status", status);
        a.put("estabelecimentoId", clinica);
        return a;
    }

    @Test
    void consultasDeClinicaComWhatsAppSaemPeloNumeroDelaEOrestoPeloGeral() {
        when(sgsmClient.listarMedicosAtivos("jwt-bot"))
                .thenReturn(List.of(Map.of("id", "m1", "nome", "Ana", "telefone", "5561911112222")));
        when(sgsmClient.listarCanaisConectados("jwt-bot")).thenReturn(List.of(
                Map.of("instancia", "sgsm-a", "estabelecimentoId", "clinica-a", "estabelecimentoNome", "Clinica A")));
        when(sgsmClient.listarAgendamentosDoDia(eq("m1"), any(), eq("jwt-bot"))).thenReturn(List.of(
                consulta("09:00", "clinica-a", "CONFIRMADO"),
                consulta("10:00", "clinica-b", "PENDENTE"),
                consulta("11:00", null, "PENDENTE"),
                consulta("12:00", "clinica-a", "CANCELADO")));

        service.enviarAgendaDoDia();

        verify(evolutionApiClient).enviarTexto(eq("sgsm-a"), eq("5561911112222"),
                argThat(t -> t.contains("na Clinica A") && t.contains("09:00") && !t.contains("10:00") && !t.contains("12:00")));
        verify(evolutionApiClient).enviarTexto(eq("5561911112222"),
                argThat(t -> t.contains("10:00") && t.contains("11:00") && !t.contains("09:00")));
    }

    @Test
    void diaSemConsultasESemCanaisSegueSoPeloGeral() {
        when(sgsmClient.listarMedicosAtivos("jwt-bot")).thenReturn(List.of(
                Map.of("id", "m1", "nome", "Ana", "telefone", "5561911112222"),
                new HashMap<>(Map.of("id", "m2", "nome", "Sem Telefone"))));
        when(sgsmClient.listarCanaisConectados("jwt-bot")).thenThrow(new ResourceAccessException("fora"));
        when(sgsmClient.listarAgendamentosDoDia(eq("m1"), any(), any())).thenReturn(List.of());

        service.enviarAgendaDoDia();

        verify(evolutionApiClient).enviarTexto(eq("5561911112222"), contains("não tem consultas"));
        verify(evolutionApiClient, never()).enviarTexto(anyString(), anyString(), anyString());
    }
}
