package br.com.sgsm.whatsapp.service;

import br.com.sgsm.whatsapp.client.AuthClient;
import br.com.sgsm.whatsapp.client.EvolutionApiClient;
import br.com.sgsm.whatsapp.client.SgsmClient;
import br.com.sgsm.whatsapp.domain.SessaoBot;
import br.com.sgsm.whatsapp.dto.AgendamentoCreateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Service
public class ExecutorAcaoService {

    private static final Logger log = LoggerFactory.getLogger(ExecutorAcaoService.class);

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
            var req = new AgendamentoCreateRequest(
                    (String) payload.get("pacienteId"),
                    (String) payload.get("servicoMedicoId"),
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

    // Gera OTP via ms-sboot-auth e envia ao usuário via WhatsApp
    public void enviarOtp(String numero, String email) {
        try {
            Map<String, Object> resp = authClient.gerarOtp(email);
            String otp = (String) resp.get("otp");
            evolutionApiClient.enviarTexto(numero,
                    "Seu código de verificação SGSM: *" + otp + "*\n\n_Válido por 5 minutos._");
        } catch (Exception e) {
            log.error("Erro ao gerar/enviar OTP para {}: {}", email, e.getMessage());
        }
    }

    private static final DateTimeFormatter FORMATO_BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final ZoneId FUSO_BRASIL = ZoneId.of("America/Sao_Paulo");

    private String combinarDataHora(String data, String hora) {
        if (data == null || hora == null) return null;
        LocalDate localDate = parseData(data);
        LocalTime localTime = parseHora(hora);
        return ZonedDateTime.of(localDate, localTime, FUSO_BRASIL)
                .toOffsetDateTime()
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private LocalDate parseData(String data) {
        if (data.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return LocalDate.parse(data);
        }
        if (data.matches("\\d{2}/\\d{2}/\\d{4}")) {
            return LocalDate.parse(data, FORMATO_BR);
        }
        throw new IllegalArgumentException("Formato de data não reconhecido: " + data);
    }

    private LocalTime parseHora(String hora) {
        return LocalTime.parse(hora.length() == 5 ? hora + ":00" : hora);
    }
}
