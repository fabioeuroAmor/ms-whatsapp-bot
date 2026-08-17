package br.com.sgsm.whatsapp.service;

import br.com.sgsm.whatsapp.client.AuthClient;
import br.com.sgsm.whatsapp.client.EvolutionApiClient;
import br.com.sgsm.whatsapp.client.SgsmClient;
import br.com.sgsm.whatsapp.domain.SessaoBot;
import br.com.sgsm.whatsapp.dto.AgendamentoCreateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Service
public class ExecutorAcaoService {

    private static final Logger log = LoggerFactory.getLogger(ExecutorAcaoService.class);
    private static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");

    private final SgsmClient sgsmClient;
    private final AuthClient authClient;
    private final EvolutionApiClient evolutionApiClient;

    public ExecutorAcaoService(SgsmClient sgsmClient,
                               AuthClient authClient,
                               EvolutionApiClient evolutionApiClient) {
        this.sgsmClient = sgsmClient;
        this.authClient = authClient;
        this.evolutionApiClient = evolutionApiClient;
    }

    public String executarAgendamento(SessaoBot sessao, Map<String, Object> payload) {
        try {
            String medicoId = (String) payload.get("medicoId");
            List<Map<String, Object>> estabelecimentos =
                    sgsmClient.listarEstabelecimentosPorMedico(medicoId, sessao.getAccessToken());
            if (estabelecimentos == null || estabelecimentos.isEmpty()) {
                log.error("Nenhum estabelecimento vinculado ao médico {}", medicoId);
                return "Não foi possível realizar o agendamento. Tente novamente ou entre em contato pelo portal.";
            }
            String estabelecimentoId = (String) estabelecimentos.get(0).get("id");

            var req = new AgendamentoCreateRequest(
                    (String) payload.get("pacienteId"),
                    (String) payload.get("servicoMedicoId"),
                    estabelecimentoId,
                    "PRESENCIAL",
                    combinarDataHora(
                            (String) payload.get("data"),
                            (String) payload.get("hora")
                    )
            );
            sgsmClient.criarAgendamento(req, sessao.getAccessToken());
            return "Agendamento confirmado com sucesso!";
        } catch (Exception e) {
            log.error("Erro ao criar agendamento: {}", e.getMessage());
            return "Não foi possível realizar o agendamento. Tente novamente ou entre em contato pelo portal.";
        }
    }

    public String executarCancelamento(SessaoBot sessao, String agendamentoId) {
        try {
            sgsmClient.cancelarAgendamento(agendamentoId, sessao.getAccessToken());
            return "Agendamento cancelado com sucesso.";
        } catch (Exception e) {
            log.error("Erro ao cancelar agendamento {}: {}", agendamentoId, e.getMessage());
            return "Não foi possível cancelar o agendamento. Tente novamente.";
        }
    }

    // Gera OTP via ms-sboot-auth e envia ao usuário via WhatsApp. Retorna true se enviado com sucesso.
    public boolean enviarOtp(String numero, String email) {
        try {
            Map<String, Object> resp = authClient.gerarOtp(email);
            String otp = (String) resp.get("otp");
            evolutionApiClient.enviarTexto(numero,
                    "Seu código de verificação SGSM: *" + otp + "*\n\n_Válido por 5 minutos._");
            return true;
        } catch (Exception e) {
            log.error("Erro ao gerar/enviar OTP para {}: {}", email, e.getMessage());
            return false;
        }
    }

    private String combinarDataHora(String data, String hora) {
        if (data == null || hora == null) return null;
        // Suporte a formatos "2026-08-10" + "14:30" → "2026-08-10T14:30:00"
        String horaFormatada = hora.contains(":") && hora.length() == 5 ? hora + ":00" : hora;
        return LocalDateTime.parse(data + "T" + horaFormatada)
                .atZone(ZONA)
                .toOffsetDateTime()
                .toString();
    }
}
