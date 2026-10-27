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
// WhatsApp por clinica (DW7): as consultas de cada clinica com numero conectado saem pelo numero
// dela; o que nao tem clinica com numero (ou e domiciliar) segue pelo canal geral do SGSM.
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
        Map<String, Map<String, Object>> canais = canaisPorClinica(token);
        log.info("Agenda diária: enviando para {} médico(s) ativo(s) referente a {} ({} clínica(s) com WhatsApp).",
                medicos.size(), hoje, canais.size());

        envioEmLoteHelper.executarComEspacamento(
                medicos,
                medico -> enviarAgendaDoMedico(medico, hoje, token, canais),
                medico -> String.valueOf(medico.get("id")));
    }

    // estabelecimentoId -> canal conectado; sem sgsm/canais, tudo segue pelo canal geral
    private Map<String, Map<String, Object>> canaisPorClinica(String token) {
        try {
            var canais = new java.util.HashMap<String, Map<String, Object>>();
            for (var c : sgsmClient.listarCanaisConectados(token)) {
                if (c.get("estabelecimentoId") != null && c.get("instancia") != null) {
                    canais.put(String.valueOf(c.get("estabelecimentoId")), c);
                }
            }
            return canais;
        } catch (Exception e) {
            log.warn("Agenda diária: não foi possível listar os WhatsApp das clínicas ({}); usando só o canal geral.",
                    e.getMessage());
            return Map.of();
        }
    }

    private void enviarAgendaDoMedico(Map<String, Object> medico, LocalDate hoje, String token,
                                      Map<String, Map<String, Object>> canais) {
        String medicoId = String.valueOf(medico.get("id"));
        String telefone = (String) medico.get("telefone");
        String nome = (String) medico.get("nome");

        if (telefone == null || telefone.isBlank()) {
            log.warn("Médico {} ({}) sem telefone cadastrado — agenda diária não enviada.", nome, medicoId);
            return;
        }

        List<Map<String, Object>> agendamentos = sgsmClient.listarAgendamentosDoDia(medicoId, hoje, token);
        var validos = agendamentos.stream()
                .filter(a -> !STATUS_IGNORADOS.contains(String.valueOf(a.get("status"))))
                .sorted(Comparator.comparing(a -> String.valueOf(a.get("dataHoraInicio"))))
                .toList();

        // consultas de clínicas com WhatsApp conectado: uma mensagem por clínica, pelo número dela
        var porClinica = new java.util.LinkedHashMap<String, List<Map<String, Object>>>();
        var geral = new java.util.ArrayList<Map<String, Object>>();
        for (var a : validos) {
            Object clinica = a.get("estabelecimentoId");
            if (clinica != null && canais.containsKey(String.valueOf(clinica))) {
                porClinica.computeIfAbsent(String.valueOf(clinica), k -> new java.util.ArrayList<>()).add(a);
            } else {
                geral.add(a);
            }
        }
        porClinica.forEach((clinica, lista) -> {
            var canal = canais.get(clinica);
            Object nomeClinica = canal.get("estabelecimentoNome");
            evolutionApiClient.enviarTexto(String.valueOf(canal.get("instancia")), telefone,
                    montarTexto(nome, hoje, lista, nomeClinica == null ? null : String.valueOf(nomeClinica)));
        });
        // o resto (e o aviso de dia sem consultas) segue pelo canal geral
        if (!geral.isEmpty() || porClinica.isEmpty()) {
            evolutionApiClient.enviarTexto(telefone, montarTexto(nome, hoje, geral, null));
        }
    }

    private String montarTexto(String nomeMedico, LocalDate data, List<Map<String, Object>> validos, String clinica) {
        if (validos.isEmpty()) {
            return "Bom dia, Dr(a). %s! Você não tem consultas agendadas para hoje (%s)."
                    .formatted(nomeMedico, data);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(clinica == null
                ? "Bom dia, Dr(a). %s! Sua agenda de hoje (%s):\n\n".formatted(nomeMedico, data)
                : "Bom dia, Dr(a). %s! Sua agenda de hoje (%s) na %s:\n\n".formatted(nomeMedico, data, clinica));
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
