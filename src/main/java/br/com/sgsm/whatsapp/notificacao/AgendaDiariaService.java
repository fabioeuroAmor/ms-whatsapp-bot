package br.com.sgsm.whatsapp.notificacao;

import br.com.sgsm.whatsapp.client.EvolutionApiClient;
import br.com.sgsm.whatsapp.client.SgsmClient;
import br.com.sgsm.whatsapp.service.SistemaAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Caso A do plano de notificacoes proativas: todo dia as 07:00, avisa cada medico ativo da
// agenda de consultas do proprio dia. So leitura - nao cria, altera nem cancela agendamento,
// so consulta o que ja existe e notifica.
@Service
public class AgendaDiariaService {

    private static final Logger log = LoggerFactory.getLogger(AgendaDiariaService.class);
    private static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final Set<String> STATUS_IGNORADOS = Set.of("CANCELADO", "NO_SHOW");

    private final SgsmClient sgsmClient;
    private final EvolutionApiClient evolutionApiClient;
    private final SistemaAuthService sistemaAuthService;
    private final EnvioEmLoteHelper envioEmLoteHelper;

    public AgendaDiariaService(SgsmClient sgsmClient, EvolutionApiClient evolutionApiClient,
                                SistemaAuthService sistemaAuthService, EnvioEmLoteHelper envioEmLoteHelper) {
        this.sgsmClient = sgsmClient;
        this.evolutionApiClient = evolutionApiClient;
        this.sistemaAuthService = sistemaAuthService;
        this.envioEmLoteHelper = envioEmLoteHelper;
    }

    public void enviarAgendaDoDia() {
        LocalDate hoje = LocalDate.now(ZONA);
        String token = sistemaAuthService.token();
        List<Map<String, Object>> medicos = sgsmClient.listarMedicosAtivos(token);
        log.info("Agenda diária: enviando para {} médico(s) ativo(s) referente a {}.", medicos.size(), hoje);

        envioEmLoteHelper.executarComEspacamento(
                medicos,
                medico -> enviarAgendaDoMedico(medico, hoje, token),
                medico -> String.valueOf(medico.get("id")));
    }

    private void enviarAgendaDoMedico(Map<String, Object> medico, LocalDate hoje, String token) {
        String medicoId = String.valueOf(medico.get("id"));
        String telefone = (String) medico.get("telefone");
        String nome = (String) medico.get("nome");

        if (telefone == null || telefone.isBlank()) {
            log.warn("Médico {} ({}) sem telefone cadastrado — agenda diária não enviada.", nome, medicoId);
            return;
        }

        List<Map<String, Object>> agendamentos = sgsmClient.listarAgendamentosDoDia(medicoId, hoje, token);
        String texto = montarTexto(nome, hoje, agendamentos);
        evolutionApiClient.enviarTexto(telefone, texto);
    }

    private String montarTexto(String nomeMedico, LocalDate data, List<Map<String, Object>> agendamentos) {
        var validos = agendamentos.stream()
                .filter(a -> !STATUS_IGNORADOS.contains(String.valueOf(a.get("status"))))
                .sorted(Comparator.comparing(a -> String.valueOf(a.get("dataHoraInicio"))))
                .toList();

        if (validos.isEmpty()) {
            return "Bom dia, Dr(a). %s! Você não tem consultas agendadas para hoje (%s)."
                    .formatted(nomeMedico, data);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Bom dia, Dr(a). %s! Sua agenda de hoje (%s):\n\n".formatted(nomeMedico, data));
        for (var a : validos) {
            String hora = OffsetDateTime.parse(String.valueOf(a.get("dataHoraInicio")))
                    .atZoneSameInstant(ZONA).format(HORA);
            String paciente = String.valueOf(a.getOrDefault("pacienteNome", "Paciente"));
            Object servico = a.get("servicoMedicoNome");
            sb.append("• %s - %s".formatted(hora, paciente));
            if (servico != null && !String.valueOf(servico).isBlank()) {
                sb.append(" (%s)".formatted(servico));
            }
            sb.append("\n");
        }
        sb.append("\nTotal: %d consulta(s).".formatted(validos.size()));
        return sb.toString();
    }
}
