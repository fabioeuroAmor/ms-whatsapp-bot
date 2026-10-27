package br.com.sgsm.whatsapp.client;

import br.com.sgsm.whatsapp.dto.ClassificarRequest;
import br.com.sgsm.whatsapp.dto.ClassificarResponse;
import br.com.sgsm.whatsapp.service.SistemaAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SgsmIaClient {

    private static final Logger log = LoggerFactory.getLogger(SgsmIaClient.class);

    private final RestClient sgsmIaClient;
    private final SistemaAuthService sistemaAuthService;

    public SgsmIaClient(@Qualifier("sgsmIaRestClient") RestClient sgsmIaClient,
                        SistemaAuthService sistemaAuthService) {
        this.sgsmIaClient = sgsmIaClient;
        this.sistemaAuthService = sistemaAuthService;
    }

    public ClassificarResponse classificar(ClassificarRequest request, String accessToken) {
        String token = accessToken != null ? accessToken : sistemaAuthService.token();
        return sgsmIaClient.post()
                .uri("/ia/whatsapp/classificar")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ClassificarResponse.class);
    }

    // Primeiro contato pelo WhatsApp de uma clínica vira lead no CRM dela (token de sistema)
    public String criarLead(String nome, String telefone, String estabelecimentoId) {
        java.util.Map<String, Object> corpo = new java.util.HashMap<>();
        corpo.put("nome", nome);
        corpo.put("telefone", telefone);
        corpo.put("origem", "WHATSAPP");
        corpo.put("observacoes", "Primeiro contato pelo WhatsApp da clínica");
        corpo.put("estabelecimentoId", estabelecimentoId);
        java.util.Map<String, Object> resposta = sgsmIaClient.post()
                .uri("/crm/leads")
                .header("Authorization", "Bearer " + sistemaAuthService.token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(corpo)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return resposta != null && resposta.get("id") != null ? resposta.get("id").toString() : null;
    }

    // O lead virou paciente (cadastro ou login pelo WhatsApp)
    public void converterLead(String leadId) {
        sgsmIaClient.patch()
                .uri("/crm/leads/{id}/status", leadId)
                .header("Authorization", "Bearer " + sistemaAuthService.token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("status", "CONVERTIDO"))
                .retrieve()
                .toBodilessEntity();
    }
}
