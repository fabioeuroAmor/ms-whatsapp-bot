package br.com.sgsm.whatsapp.client;

import br.com.sgsm.whatsapp.dto.AgendamentoCreateRequest;
import br.com.sgsm.whatsapp.dto.PacienteCreateRequest;
import br.com.sgsm.whatsapp.dto.ReservaCreateRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Component
public class SgsmClient {

    private final RestClient sgsmClient;

    public SgsmClient(@Qualifier("sgsmRestClient") RestClient sgsmClient) {
        this.sgsmClient = sgsmClient;
    }

    public Map<String, Object> criarPaciente(PacienteCreateRequest req, String accessToken) {
        return sgsmClient.post()
                .uri("/v1/api/pacientes")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public List<Map<String, Object>> listarAgendamentos(String accessToken) {
        return sgsmClient.get()
                .uri("/v1/api/agendamentos")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public Map<String, Object> criarAgendamento(AgendamentoCreateRequest req, String accessToken) {
        return sgsmClient.post()
                .uri("/v1/api/agendamentos")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    // Segura o horário por alguns minutos enquanto o paciente confirma (409 = ocupado, com alternativas)
    public Map<String, Object> reservarHorario(ReservaCreateRequest req, String accessToken) {
        return sgsmClient.post()
                .uri("/v1/api/agendamentos/reservas")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public void liberarReserva(String reservaToken, String accessToken) {
        sgsmClient.delete()
                .uri("/v1/api/agendamentos/reservas/" + reservaToken)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .toBodilessEntity();
    }

    // ── WhatsApp por clínica (endpoints internos do sgsm, token de sistema) ───────────────────

    public Map<String, Object> resolverCanal(String instancia, String sistemaToken) {
        return sgsmClient.get()
                .uri("/v1/api/interno/whatsapp/canais/{instancia}", instancia)
                .header("Authorization", "Bearer " + sistemaToken)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public List<Map<String, Object>> listarCanaisConectados(String sistemaToken) {
        return sgsmClient.get()
                .uri("/v1/api/interno/whatsapp/canais")
                .header("Authorization", "Bearer " + sistemaToken)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public void vincularPaciente(String pacienteId, String estabelecimentoId, String telefone, String sistemaToken) {
        sgsmClient.post()
                .uri("/v1/api/interno/whatsapp/vinculos")
                .header("Authorization", "Bearer " + sistemaToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("pacienteId", pacienteId, "estabelecimentoId", estabelecimentoId,
                        "origem", "WHATSAPP", "telefone", telefone))
                .retrieve()
                .toBodilessEntity();
    }

    public boolean consentimentoVigente(String telefone, String estabelecimentoId, String sistemaToken) {
        Map<String, Object> resposta = sgsmClient.get()
                .uri(uri -> uri.path("/v1/api/interno/whatsapp/consentimentos")
                        .queryParam("telefone", telefone)
                        .queryParam("estabelecimentoId", estabelecimentoId)
                        .queryParam("canal", "WHATSAPP")
                        .build())
                .header("Authorization", "Bearer " + sistemaToken)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return resposta != null && Boolean.TRUE.equals(resposta.get("vigente"));
    }

    public void registrarConsentimento(String telefone, String estabelecimentoId, String versao, String sistemaToken) {
        sgsmClient.post()
                .uri("/v1/api/interno/whatsapp/consentimentos")
                .header("Authorization", "Bearer " + sistemaToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("telefone", telefone, "estabelecimentoId", estabelecimentoId,
                        "canal", "WHATSAPP", "versaoTexto", versao))
                .retrieve()
                .toBodilessEntity();
    }

    // Canal geral do SGSM: o paciente escolhe a clínica antes de agendar
    public List<Map<String, Object>> listarEstabelecimentosAtivos(String sistemaToken) {
        return sgsmClient.get()
                .uri(uri -> uri.path("/v1/api/estabelecimentos").queryParam("ativo", true).build())
                .header("Authorization", "Bearer " + sistemaToken)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    // Agenda diária por clínica: médicos que atendem na clínica
    public List<Map<String, Object>> listarMedicosDoEstabelecimento(String estabelecimentoId, String sistemaToken) {
        return sgsmClient.get()
                .uri("/v1/api/estabelecimentos/{id}/medicos", estabelecimentoId)
                .header("Authorization", "Bearer " + sistemaToken)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public void cancelarAgendamento(String agendamentoId, String accessToken) {
        sgsmClient.patch()
                .uri("/v1/api/agendamentos/" + agendamentoId + "/cancelar")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .toBodilessEntity();
    }

    // Usado pela agenda diária (07:00): destinatários da notificação.
    public List<Map<String, Object>> listarMedicosAtivos(String accessToken) {
        return sgsmClient.get()
                .uri(uriBuilder -> uriBuilder.path("/v1/api/medicos").queryParam("ativo", true).build())
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    // Usado pela agenda diária (07:00): consultas do médico só no dia informado.
    public List<Map<String, Object>> listarAgendamentosDoDia(String medicoId, LocalDate data, String accessToken) {
        return sgsmClient.get()
                .uri(uriBuilder -> uriBuilder.path("/v1/api/agendamentos")
                        .queryParam("medicoId", medicoId)
                        .queryParam("data", data)
                        .build())
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }
}
