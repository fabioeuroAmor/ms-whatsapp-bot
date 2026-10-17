package br.com.sgsm.whatsapp.service;

import br.com.sgsm.whatsapp.client.AuthClient;
import br.com.sgsm.whatsapp.client.EvolutionApiClient;
import br.com.sgsm.whatsapp.client.SgsmClient;
import br.com.sgsm.whatsapp.domain.SessaoBot;
import br.com.sgsm.whatsapp.dto.AgendamentoCreateRequest;
import br.com.sgsm.whatsapp.dto.ReservaCreateRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
public class ExecutorAcaoService {

    private static final Logger log = LoggerFactory.getLogger(ExecutorAcaoService.class);

    public static final String CHAVE_RESERVA = "reservaToken";

    private final SgsmClient sgsmClient;
    private final AuthClient authClient;
    private final EvolutionApiClient evolutionApiClient;
    private final SistemaAuthService sistemaAuthService;
    private final ObjectMapper objectMapper;

    public ExecutorAcaoService(SgsmClient sgsmClient,
                               AuthClient authClient,
                               EvolutionApiClient evolutionApiClient,
                               SistemaAuthService sistemaAuthService,
                               ObjectMapper objectMapper) {
        this.sgsmClient = sgsmClient;
        this.authClient = authClient;
        this.evolutionApiClient = evolutionApiClient;
        this.sistemaAuthService = sistemaAuthService;
        this.objectMapper = objectMapper;
    }

    public enum SituacaoReserva { RESERVADO, INDISPONIVEL, SEM_RESERVA }

    // token: só em RESERVADO; mensagem: só em INDISPONIVEL (horário ocupado + alternativas)
    public record ResultadoReserva(SituacaoReserva situacao, String token, String mensagem) {}

    // Pré-reserva o horário antes de pedir a confirmação ao paciente, para a secretária não
    // ocupá-lo nesse meio tempo. Falha técnica não bloqueia o fluxo: o POST de agendamento
    // continua protegido pela constraint do banco (V013).
    public ResultadoReserva reservar(SessaoBot sessao, Map<String, Object> payload) {
        try {
            var req = new ReservaCreateRequest(
                    (String) payload.get("servicoMedicoId"),
                    combinarDataHora((String) payload.get("data"), (String) payload.get("hora")));
            Map<String, Object> resp = sgsmClient.reservarHorario(req, sessao.getAccessToken());
            if (resp != null && Boolean.TRUE.equals(resp.get("reservado")) && resp.get("token") != null) {
                return new ResultadoReserva(SituacaoReserva.RESERVADO, resp.get("token").toString(), null);
            }
            return new ResultadoReserva(SituacaoReserva.SEM_RESERVA, null, null);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
                return new ResultadoReserva(SituacaoReserva.INDISPONIVEL, null, mensagemIndisponivel(e));
            }
            log.warn("Pré-reserva recusada ({}): {}", e.getStatusCode(), e.getMessage());
            return new ResultadoReserva(SituacaoReserva.SEM_RESERVA, null, null);
        } catch (Exception e) {
            log.warn("Pré-reserva indisponível, seguindo sem reserva: {}", e.getMessage());
            return new ResultadoReserva(SituacaoReserva.SEM_RESERVA, null, null);
        }
    }

    // Melhor esforço: se falhar, a reserva expira sozinha no Redis
    public void liberarReserva(SessaoBot sessao, String reservaToken) {
        if (reservaToken == null) return;
        try {
            sgsmClient.liberarReserva(reservaToken, sessao.getAccessToken());
        } catch (Exception e) {
            log.debug("Falha ao liberar pré-reserva {}: {}", reservaToken, e.getMessage());
        }
    }

    public String executarAgendamento(SessaoBot sessao, Map<String, Object> payload) {
        String dataHora;
        try {
            dataHora = combinarDataHora((String) payload.get("data"), (String) payload.get("hora"));
        } catch (Exception e) {
            log.error("Data/hora inválida no agendamento: {}", e.getMessage());
            return "Não foi possível realizar o agendamento. Tente novamente ou entre em contato pelo portal.";
        }
        String reservaToken = (String) payload.get(CHAVE_RESERVA);
        try {
            criarAgendamento(sessao, payload, dataHora, reservaToken);
            return "Agendamento confirmado com sucesso!";
        } catch (HttpClientErrorException e) {
            // Reserva expirada (passou do prazo de confirmação): tenta sem ela; o horário
            // pode continuar livre, e se não estiver o sgsm devolve 409
            if (reservaToken != null && e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                try {
                    criarAgendamento(sessao, payload, dataHora, null);
                    return "Agendamento confirmado com sucesso!";
                } catch (HttpClientErrorException e2) {
                    return mensagemDeFalha(e2);
                } catch (Exception e2) {
                    log.error("Erro ao criar agendamento sem reserva: {}", e2.getMessage());
                    return "Não foi possível realizar o agendamento. Tente novamente ou entre em contato pelo portal.";
                }
            }
            return mensagemDeFalha(e);
        } catch (Exception e) {
            log.error("Erro ao criar agendamento: {}", e.getMessage());
            return "Não foi possível realizar o agendamento. Tente novamente ou entre em contato pelo portal.";
        }
    }

    private void criarAgendamento(SessaoBot sessao, Map<String, Object> payload, String dataHora, String reservaToken) {
        var req = new AgendamentoCreateRequest(
                (String) payload.get("pacienteId"),
                (String) payload.get("servicoMedicoId"),
                dataHora,
                reservaToken);
        sgsmClient.criarAgendamento(req, sessao.getAccessToken());
    }

    private String mensagemDeFalha(HttpClientErrorException e) {
        if (e.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
            return mensagemIndisponivel(e);
        }
        log.error("Erro ao criar agendamento: {}", e.getMessage());
        return "Não foi possível realizar o agendamento. Tente novamente ou entre em contato pelo portal.";
    }

    // Monta "horário ocupado" + até 3 alternativas vindas do corpo do 409 (ProblemDetail.alternativas)
    String mensagemIndisponivel(HttpClientErrorException e) {
        List<String> opcoes = alternativas(e.getResponseBodyAsString());
        if (opcoes.isEmpty()) {
            return "Esse horário acabou de ser ocupado e não encontrei outro próximo. "
                    + "Me diga outra data ou horário que eu verifico.";
        }
        var sb = new StringBuilder("Esse horário acabou de ser ocupado. Tenho estas opções próximas:\n");
        for (int i = 0; i < opcoes.size(); i++) {
            sb.append("\n*").append(i + 1).append(".* ").append(opcoes.get(i));
        }
        return sb.append("\n\nQual delas prefere? Me diga a data e o horário.").toString();
    }

    private List<String> alternativas(String corpo) {
        if (corpo == null || corpo.isBlank()) return List.of();
        try {
            Map<String, Object> problema = objectMapper.readValue(corpo, new TypeReference<>() {});
            Object lista = problema.get("alternativas");
            if (lista == null && problema.get("properties") instanceof Map<?, ?> props) {
                lista = props.get("alternativas");
            }
            if (!(lista instanceof List<?> itens)) return List.of();
            return itens.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> ((Map<?, ?>) item).get("dataHoraInicio"))
                    .filter(java.util.Objects::nonNull)
                    .map(inicio -> OffsetDateTime.parse(inicio.toString())
                            .atZoneSameInstant(FUSO_BRASIL)
                            .format(FORMATO_ALTERNATIVA))
                    .limit(3)
                    .toList();
        } catch (Exception ex) {
            log.debug("Corpo do 409 sem alternativas legíveis: {}", ex.getMessage());
            return List.of();
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
            Map<String, Object> resp = authClient.gerarOtp(email, sistemaAuthService.token());
            String otp = (String) resp.get("otp");
            evolutionApiClient.enviarTexto(numero,
                    "Seu código de verificação SGSM: *" + otp + "*\n\n_Válido por 5 minutos._");
        } catch (Exception e) {
            log.error("Erro ao gerar/enviar OTP para {}: {}", email, e.getMessage());
        }
    }

    private static final DateTimeFormatter FORMATO_BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter FORMATO_ALTERNATIVA = DateTimeFormatter.ofPattern("dd/MM 'às' HH:mm");
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
