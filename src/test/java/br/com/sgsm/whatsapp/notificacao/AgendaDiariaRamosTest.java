package br.com.sgsm.whatsapp.notificacao;

import br.com.sgsm.whatsapp.client.EvolutionApiClient;
import br.com.sgsm.whatsapp.client.SgsmClient;
import br.com.sgsm.whatsapp.service.SistemaAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// Agenda diária: canais incompletos, clínica sem nome, médico sem telefone e serviço na linha da consulta
@ExtendWith(MockitoExtension.class)
class AgendaDiariaRamosTest {

    @Mock private SgsmClient sgsmClient;
    @Mock private EvolutionApiClient evolutionApiClient;
    @Mock private SistemaAuthService sistemaAuthService;

    private AgendaDiariaService service;

    @BeforeEach
    void setUp() {
        // envio real em lote (com espaçamento), para exercitar também o identificador do item em falha
        service = new AgendaDiariaService(sgsmClient, evolutionApiClient, sistemaAuthService, new EnvioEmLoteHelper());
        when(sistemaAuthService.token()).thenReturn("jwt-bot");
    }

    private static Map<String, Object> consulta(String hora, String clinica, String servico) {
        var a = new HashMap<String, Object>();
        a.put("dataHoraInicio", "2026-10-27T" + hora + ":00-03:00");
        a.put("pacienteNome", "Paciente " + hora);
        a.put("status", "CONFIRMADO");
        a.put("estabelecimentoId", clinica);
        a.put("servicoMedicoNome", servico);
        return a;
    }

    @Test
    void canalSemNomeDaClinicaEServicoNaLinhaDaConsulta() {
        var semNome = new HashMap<String, Object>(Map.of("instancia", "sgsm-a", "estabelecimentoId", "clinica-a"));
        when(sgsmClient.listarMedicosAtivos("jwt-bot")).thenReturn(List.of(
                Map.of("id", "m1", "nome", "Ana", "telefone", "5561911112222")));
        when(sgsmClient.listarCanaisConectados("jwt-bot")).thenReturn(List.of(
                semNome,
                new HashMap<>(Map.of("instancia", "sgsm-sem-clinica")),
                new HashMap<>(Map.of("estabelecimentoId", "clinica-sem-instancia"))));
        when(sgsmClient.listarAgendamentosDoDia(eq("m1"), any(), eq("jwt-bot"))).thenReturn(List.of(
                consulta("09:00", "clinica-a", "Consulta de rotina"),
                consulta("10:00", "clinica-a", " "),
                consulta("11:00", "clinica-sem-instancia", null)));

        service.enviarAgendaDoDia();

        verify(evolutionApiClient).enviarTexto(eq("sgsm-a"), eq("5561911112222"), argThat(t ->
                t.contains("Sua agenda de hoje") && !t.contains(" na ")
                        && t.contains("09:00 - Paciente 09:00 (Consulta de rotina)")
                        && t.contains("10:00 - Paciente 10:00\n")));
        verify(evolutionApiClient).enviarTexto(eq("5561911112222"), argThat(t -> t.contains("11:00")));
    }

    @Test
    void medicoSemTelefoneEFalhaDeUmMedicoNaoParamOsOutros() {
        when(sgsmClient.listarMedicosAtivos("jwt-bot")).thenReturn(List.of(
                new HashMap<>(Map.of("id", "m0", "nome", "Sem Fone", "telefone", " ")),
                Map.of("id", "m1", "nome", "Falha", "telefone", "5561900000001"),
                Map.of("id", "m2", "nome", "Bia", "telefone", "5561900000002")));
        when(sgsmClient.listarCanaisConectados("jwt-bot")).thenReturn(List.of());
        when(sgsmClient.listarAgendamentosDoDia(eq("m1"), any(), any())).thenThrow(new IllegalStateException("sgsm fora"));
        when(sgsmClient.listarAgendamentosDoDia(eq("m2"), any(), any())).thenReturn(List.of());

        service.enviarAgendaDoDia();

        verify(sgsmClient, never()).listarAgendamentosDoDia(eq("m0"), any(), any());
        verify(evolutionApiClient).enviarTexto(eq("5561900000002"), contains("não tem consultas"));
        verify(evolutionApiClient, never()).enviarTexto(eq("5561900000001"), anyString());
    }
}
