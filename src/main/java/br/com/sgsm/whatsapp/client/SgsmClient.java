package br.com.sgsm.whatsapp.client;

import br.com.sgsm.whatsapp.dto.AgendamentoCreateRequest;
import br.com.sgsm.whatsapp.dto.PacienteCreateRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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

    public List<Map<String, Object>> listarEstabelecimentosPorMedico(String medicoId, String accessToken) {
        return sgsmClient.get()
                .uri("/v1/api/agendamentos/medico/{medicoId}/estabelecimentos", medicoId)
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

    public void cancelarAgendamento(String agendamentoId, String accessToken) {
        sgsmClient.patch()
                .uri("/v1/api/agendamentos/" + agendamentoId + "/cancelar")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .toBodilessEntity();
    }
}
