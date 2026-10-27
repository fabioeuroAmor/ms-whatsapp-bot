package br.com.sgsm.whatsapp.service;

import br.com.sgsm.whatsapp.client.AuthClient;
import br.com.sgsm.whatsapp.client.EvolutionApiClient;
import br.com.sgsm.whatsapp.client.SgsmClient;
import br.com.sgsm.whatsapp.client.SgsmIaClient;
import br.com.sgsm.whatsapp.config.BotProperties;
import br.com.sgsm.whatsapp.domain.ConfirmacaoPendente;
import br.com.sgsm.whatsapp.domain.MensagemHistorico;
import br.com.sgsm.whatsapp.domain.SessaoBot;
import br.com.sgsm.whatsapp.dto.AuthRegistrarRequest;
import br.com.sgsm.whatsapp.dto.ClassificarRequest;
import br.com.sgsm.whatsapp.dto.ClassificarResponse;
import br.com.sgsm.whatsapp.dto.EntidadesExtraidas;
import br.com.sgsm.whatsapp.dto.PacienteCreateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BotOrquestradorService {

    private static final Logger log = LoggerFactory.getLogger(BotOrquestradorService.class);

    private final SessaoService sessaoService;
    private final SgsmIaClient sgsmIaClient;
    private final EvolutionApiClient evolutionApiClient;
    private final ExecutorAcaoService executorAcaoService;
    private final AuthClient authClient;
    private final SgsmClient sgsmClient;
    private final BotProperties props;
    private final SistemaAuthService sistemaAuthService;
    private final ObjectMapper objectMapper;

    public BotOrquestradorService(SessaoService sessaoService,
                                  SgsmIaClient sgsmIaClient,
                                  EvolutionApiClient evolutionApiClient,
                                  ExecutorAcaoService executorAcaoService,
                                  AuthClient authClient,
                                  SgsmClient sgsmClient,
                                  BotProperties props,
                                  SistemaAuthService sistemaAuthService,
                                  ObjectMapper objectMapper) {
        this.sessaoService = sessaoService;
        this.sgsmIaClient = sgsmIaClient;
        this.evolutionApiClient = evolutionApiClient;
        this.executorAcaoService = executorAcaoService;
        this.authClient = authClient;
        this.sgsmClient = sgsmClient;
        this.props = props;
        this.sistemaAuthService = sistemaAuthService;
        this.objectMapper = objectMapper;
    }

    public void processar(String numero, String texto, String nomeUsuario) {
        processar(numero, texto, nomeUsuario, new CanalService.Canal(null, null, null));
    }

    // canal: número (instância) que recebeu a mensagem. No WhatsApp de uma clínica a conversa, o
    // consentimento, o lead e o agendamento ficam ligados a ela; no canal geral, à clínica escolhida.
    public void processar(String numero, String texto, String nomeUsuario, CanalService.Canal canal) {
        SessaoBot sessao = sessaoService.obter(numero, canal.instancia());
        if (!canal.geral()) {
            sessao.setInstancia(canal.instancia());
            sessao.setEstabelecimentoId(canal.estabelecimentoId());
            sessao.setEstabelecimentoNome(canal.estabelecimentoNome());
        }
        if (nomeUsuario != null && sessao.getNomeUsuario() == null) {
            sessao.setNomeUsuario(nomeUsuario);
        }

        // Consentimento já dado a esta clínica (DW6) vale para as próximas conversas
        if (!sessao.isConsentimentoAceito() && sessao.getInstancia() != null && consentimentoVigente(sessao)) {
            sessao.setConsentimentoAceito(true);
        }

        // 1. Consentimento LGPD (primeiro contato)
        if (!sessao.isConsentimentoAceito()) {
            if ("ACEITO".equalsIgnoreCase(texto.trim())) {
                sessao.setConsentimentoAceito(true);
                registrarConsentimentoNaClinica(sessao);
                criarLeadSeNovo(sessao);
                sessaoService.salvar(sessao);
                responder(sessao, menuPrincipal(sessao));
            } else {
                responder(sessao, props.consentimento().mensagem());
                sessaoService.salvar(sessao);
            }
            return;
        }

        // 2. Consentimento LGPD do cadastro clínico, se pendente (distinto do consentimento
        // de canal acima: este é sobre nome/CPF/dados de saúde, disparado só quando o
        // usuário tenta se cadastrar — ver ramo CADASTRAR do switch abaixo).
        if (sessao.getConfirmacaoPendente() != null
                && "CONSENTIMENTO_CADASTRO".equals(sessao.getConfirmacaoPendente().getTipo())) {
            if ("ACEITO".equalsIgnoreCase(texto.trim())) {
                sessao.setConsentimentoCadastroAceito(true);
                sessao.setConfirmacaoPendente(null);
                sessaoService.salvar(sessao);
                responder(sessao,
                        "Consentimento registrado! Pode me informar seu nome completo, CPF, data de nascimento e e-mail para o cadastro.");
            } else {
                responder(sessao, props.consentimentoCadastro().mensagem());
                sessaoService.salvar(sessao);
            }
            return;
        }

        // 3. Se há OTP pendente, verificar
        if (sessao.getConfirmacaoPendente() != null
                && "OTP".equals(sessao.getConfirmacaoPendente().getTipo())) {
            processarOtp(numero, texto, sessao);
            return;
        }

        // Canal geral: resposta à pergunta "em qual clínica?" feita antes de agendar (DW4)
        if (sessao.getConfirmacaoPendente() != null
                && "ESCOLHER_CLINICA".equals(sessao.getConfirmacaoPendente().getTipo())) {
            responder(sessao, escolherClinica(sessao, texto));
            sessaoService.salvar(sessao);
            return;
        }

        // Paciente com conta (site/clínica) ainda não autenticado que manda só o e-mail: inicia o login
        // sem depender da IA classificar a mensagem como AUTENTICAR
        if (sessao.getAccessToken() == null && sessao.getConfirmacaoPendente() == null
                && (sessao.getDadosCadastro() == null || sessao.getDadosCadastro().isEmpty())
                && EMAIL.matcher(texto.trim()).matches()) {
            String resposta = iniciarLoginPorEmail(sessao, texto.trim());
            sessaoService.salvar(sessao);
            responder(sessao, resposta);
            return;
        }

        // 3. RAG: Classificar intenção via sgsm-ia
        var classificarReq = new ClassificarRequest(
                texto,
                sessao.getHistorico() != null
                        ? sessao.getHistorico().stream()
                                .map(m -> new MensagemHistorico(m.getPapel(), m.getTexto()))
                                .toList()
                        : List.of(),
                sessao.getPerfil(),
                sessao.getConfirmacaoPendente() != null
                        ? serializarConfirmacao(sessao.getConfirmacaoPendente())
                        : null,
                sessao.getEstabelecimentoId(),
                sessao.getEstabelecimentoNome()
        );

        ClassificarResponse resp = sgsmIaClient.classificar(classificarReq, sessao.getAccessToken());
        if (resp == null) {
            responder(sessao,
                    "Serviço temporariamente indisponível. Tente novamente em alguns instantes.");
            return;
        }

        log.debug("Intent detectado para {}: {}", numero, resp.getIntent());

        // 4. Atualiza histórico
        sessaoService.adicionarHistorico(sessao, "USUARIO", texto);
        if (resp.getRespostaUsuario() != null) {
            sessaoService.adicionarHistorico(sessao, "ASSISTENTE", resp.getRespostaUsuario());
        }

        // 5. Executa ação baseada no intent
        String respostaFinal = switch (resp.getIntent()) {
            case CONSULTAR, CONVERSAR -> resp.getRespostaUsuario();

            case AUTENTICAR -> {
                if (sessao.getAccessToken() == null && resp.getEntidades() != null
                        && resp.getEntidades().getEmail() != null && !resp.getEntidades().getEmail().isBlank()) {
                    yield iniciarLoginPorEmail(sessao, resp.getEntidades().getEmail().trim());
                }
                if (sessao.getAccessToken() == null) {
                    yield resp.getRespostaUsuario()
                            + "\n\n_Digite seu e-mail cadastrado para receber um código de verificação._";
                }
                yield "Você já está autenticado como " + sessao.getPerfil() + ".";
            }

            case AGENDAR -> {
                if (sessao.getAccessToken() == null) {
                    yield "Para agendar, primeiro preciso confirmar sua identidade.\n_Digite seu e-mail cadastrado:_";
                }
                if (sessao.getEstabelecimentoId() == null) {
                    yield pedirClinica(sessao);
                }
                if (!resp.getDadosFaltantes().isEmpty() || !resp.isRequerConfirmacao()) {
                    yield resp.getRespostaUsuario();
                }
                // Todos os dados presentes: pré-reserva o horário e salva a confirmação pendente.
                // Um pedido anterior ainda não confirmado é substituído (libera a reserva antiga).
                var payload = montarPayloadAgendamento(resp.getEntidades(), sessao);
                if (faltaDadoDoAgendamento(payload)) {
                    // a IA marcou "confirmar" sem serviço/data/hora: o sgsm recusaria o agendamento
                    yield "Para agendar, preciso saber o serviço (ou consulta), a data e o horário. "
                            + "Pode me dizer qual consulta deseja e quando?";
                }
                liberarReservaPendente(sessao);
                var reserva = executorAcaoService.reservar(sessao, payload);
                if (reserva.situacao() == ExecutorAcaoService.SituacaoReserva.INDISPONIVEL) {
                    sessao.setConfirmacaoPendente(null);
                    sessaoService.adicionarHistorico(sessao, "ASSISTENTE", reserva.mensagem());
                    yield reserva.mensagem();
                }
                if (reserva.token() != null) {
                    payload.put(ExecutorAcaoService.CHAVE_RESERVA, reserva.token());
                }
                sessao.setConfirmacaoPendente(new ConfirmacaoPendente("AGENDAR", payload));
                yield resp.getRespostaUsuario();
            }

            case CANCELAR -> {
                if (sessao.getAccessToken() == null) {
                    yield "Para cancelar, primeiro preciso confirmar sua identidade.\n_Digite seu e-mail cadastrado:_";
                }
                if (resp.getEntidades() != null && resp.getEntidades().getAgendamentoId() != null) {
                    sessao.setConfirmacaoPendente(new ConfirmacaoPendente("CANCELAR",
                            Map.of("agendamentoId", resp.getEntidades().getAgendamentoId())));
                }
                yield resp.getRespostaUsuario();
            }

            case CADASTRAR -> {
                // LGPD 3.1: consentimento clínico é obrigatório antes de coletar qualquer
                // dado de cadastro — a próxima mensagem do usuário é tratada pelo gate
                // CONSENTIMENTO_CADASTRO no topo de processar(), não pelo RAG.
                if (!sessao.isConsentimentoCadastroAceito()) {
                    sessao.setConfirmacaoPendente(new ConfirmacaoPendente("CONSENTIMENTO_CADASTRO", Map.of()));
                    yield props.consentimentoCadastro().mensagem();
                }
                // Coleta dados progressivamente turno a turno
                atualizarDadosCadastro(sessao, resp.getEntidades());
                if (resp.isRequerConfirmacao()) {
                    sessao.setConfirmacaoPendente(new ConfirmacaoPendente("CADASTRAR",
                            new HashMap<>(toObjectMap(sessao.getDadosCadastro()))));
                }
                yield resp.getRespostaUsuario();
            }

            case ATIVAR_FUNC -> resp.getRespostaUsuario();

            case CONFIRMAR -> {
                if (sessao.getConfirmacaoPendente() == null) {
                    yield "Não há nenhuma ação pendente para confirmar.";
                }
                String tipoAnterior = sessao.getConfirmacaoPendente().getTipo();
                String resultado = executarConfirmacao(sessao);
                // Só limpa se a execução não tiver transicionado para um novo estado pendente
                // (ex.: CADASTRAR com sucesso vira OTP dentro de executarCadastro())
                if (sessao.getConfirmacaoPendente() != null
                        && tipoAnterior.equals(sessao.getConfirmacaoPendente().getTipo())) {
                    sessao.setConfirmacaoPendente(null);
                }
                yield resultado;
            }

            case RECUSAR -> {
                liberarReservaPendente(sessao);
                sessao.setConfirmacaoPendente(null);
                yield "Tudo bem! " + menuPrincipal(sessao);
            }
        };

        sessaoService.salvar(sessao);
        responder(sessao, respostaFinal != null ? respostaFinal : "...");
    }

    // --- Métodos privados de suporte ---

    static final String VERSAO_TERMO_WHATSAPP = "whatsapp-lgpd-v1";

    // Responde pelo mesmo número que recebeu a conversa (instância null = canal geral)
    private void responder(SessaoBot sessao, String texto) {
        if (sessao.getInstancia() == null) {
            evolutionApiClient.enviarTexto(sessao.getNumero(), texto);
        } else {
            evolutionApiClient.enviarTexto(sessao.getInstancia(), sessao.getNumero(), texto);
        }
    }

    private boolean consentimentoVigente(SessaoBot sessao) {
        try {
            return sgsmClient.consentimentoVigente(sessao.getNumero(), sessao.getEstabelecimentoId(), sistemaAuthService.token());
        } catch (Exception e) {
            log.warn("Não foi possível consultar o consentimento da sessão {}: {}", sessao.getNumero(), e.getMessage());
            return false; // na dúvida, pede o aceite de novo
        }
    }

    private void registrarConsentimentoNaClinica(SessaoBot sessao) {
        if (sessao.getInstancia() == null) return;
        try {
            sgsmClient.registrarConsentimento(sessao.getNumero(), sessao.getEstabelecimentoId(), VERSAO_TERMO_WHATSAPP,
                    sistemaAuthService.token());
        } catch (Exception e) {
            log.error("Falha ao registrar o consentimento da sessão {} na clínica {}: {}",
                    sessao.getNumero(), sessao.getEstabelecimentoId(), e.getMessage());
        }
    }

    // Primeiro contato por este número no WhatsApp da clínica: lead no CRM dela (uma vez por número)
    private void criarLeadSeNovo(SessaoBot sessao) {
        if (sessao.getInstancia() == null || sessaoService.leadDe(sessao.getEstabelecimentoId(), sessao.getNumero()) != null) {
            return;
        }
        try {
            String nome = sessao.getNomeUsuario() != null ? sessao.getNomeUsuario() : "Contato WhatsApp";
            String leadId = sgsmIaClient.criarLead(nome, sessao.getNumero(), sessao.getEstabelecimentoId());
            if (leadId != null) {
                sessaoService.guardarLead(sessao.getEstabelecimentoId(), sessao.getNumero(), leadId);
            }
        } catch (Exception e) {
            log.warn("Falha ao criar o lead da sessão {}: {}", sessao.getNumero(), e.getMessage());
        }
    }

    // Paciente identificado (cadastro ou login): vínculo com a clínica (DW5) e lead convertido
    private void vincularAClinica(SessaoBot sessao, String pacienteId) {
        if (sessao.getEstabelecimentoId() == null || pacienteId == null) return;
        try {
            sgsmClient.vincularPaciente(pacienteId, sessao.getEstabelecimentoId(), sessao.getNumero(), sistemaAuthService.token());
        } catch (Exception e) {
            log.warn("Falha ao vincular o paciente {} à clínica {}: {}", pacienteId, sessao.getEstabelecimentoId(), e.getMessage());
        }
        String lead = sessaoService.leadDe(sessao.getEstabelecimentoId(), sessao.getNumero());
        if (lead != null && !LEAD_CONVERTIDO.equals(lead)) {
            try {
                sgsmIaClient.converterLead(lead);
                sessaoService.guardarLead(sessao.getEstabelecimentoId(), sessao.getNumero(), LEAD_CONVERTIDO);
            } catch (Exception e) {
                log.warn("Falha ao converter o lead {}: {}", lead, e.getMessage());
            }
        }
    }

    static final String LEAD_CONVERTIDO = "CONVERTIDO";

    // Canal geral: lista as clínicas para o paciente escolher onde agendar
    private String pedirClinica(SessaoBot sessao) {
        List<Map<String, Object>> clinicas;
        try {
            clinicas = sgsmClient.listarEstabelecimentosAtivos(sistemaAuthService.token());
        } catch (Exception e) {
            log.warn("Falha ao listar clínicas para a sessão {}: {}", sessao.getNumero(), e.getMessage());
            return "Não consegui carregar as clínicas agora. Tente novamente em alguns minutos.";
        }
        if (clinicas == null || clinicas.isEmpty()) {
            return "No momento não há clínicas disponíveis para agendamento por aqui.";
        }
        var opcoes = new java.util.ArrayList<Map<String, Object>>();
        var texto = new StringBuilder("Em qual clínica você quer ser atendido(a)?\n");
        for (var c : clinicas.stream().limit(10).toList()) {
            opcoes.add(Map.of("id", String.valueOf(c.get("id")), "nome", String.valueOf(c.get("nome"))));
            texto.append("\n*").append(opcoes.size()).append(".* ").append(c.get("nome"));
        }
        sessao.setConfirmacaoPendente(new ConfirmacaoPendente("ESCOLHER_CLINICA", Map.of("opcoes", opcoes)));
        return texto.append("\n\nResponda com o número da clínica.").toString();
    }

    @SuppressWarnings("unchecked")
    private String escolherClinica(SessaoBot sessao, String texto) {
        var opcoes = (List<Map<String, Object>>) sessao.getConfirmacaoPendente().getPayload().get("opcoes");
        int escolha;
        try {
            escolha = Integer.parseInt(texto.trim().replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            escolha = -1;
        }
        if (opcoes == null || escolha < 1 || escolha > opcoes.size()) {
            return "Não entendi. Responda só com o número da clínica da lista.";
        }
        var clinica = opcoes.get(escolha - 1);
        sessao.setEstabelecimentoId(String.valueOf(clinica.get("id")));
        sessao.setEstabelecimentoNome(String.valueOf(clinica.get("nome")));
        sessao.setConfirmacaoPendente(null);
        return "Certo! Vou agendar na *" + sessao.getEstabelecimentoNome()
                + "*. Me diga a consulta, a data e o horário que você quer.";
    }

    private static final java.util.regex.Pattern EMAIL = java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    // O código vai para o E-MAIL da conta (nunca para este WhatsApp): quem digitar o e-mail de outra
    // pessoa não recebe o código. A resposta não revela se o e-mail tem conta.
    private String iniciarLoginPorEmail(SessaoBot sessao, String email) {
        try {
            authClient.enviarOtpPorEmail(email, sistemaAuthService.token());
        } catch (Exception e) {
            log.warn("Falha ao pedir código de login por e-mail para a sessão {}: {}", sessao.getNumero(), e.getMessage());
            return "Não consegui enviar o código agora. Tente novamente em alguns minutos.";
        }
        sessao.setConfirmacaoPendente(new ConfirmacaoPendente("OTP", Map.of("email", email)));
        return "Se houver uma conta com o e-mail " + email + ", enviei para ele um código de 6 dígitos "
                + "(vale 5 minutos). Digite o código aqui para entrar.";
    }

    private static boolean faltaDadoDoAgendamento(Map<String, Object> payload) {
        for (String campo : List.of("servicoMedicoId", "data", "hora")) {
            Object valor = payload.get(campo);
            if (valor == null || valor.toString().isBlank()) return true;
        }
        return false;
    }

    private void liberarReservaPendente(SessaoBot sessao) {
        var pendente = sessao.getConfirmacaoPendente();
        if (pendente != null && "AGENDAR".equals(pendente.getTipo()) && pendente.getPayload() != null) {
            Object token = pendente.getPayload().get(ExecutorAcaoService.CHAVE_RESERVA);
            if (token != null) {
                executorAcaoService.liberarReserva(sessao, token.toString());
            }
        }
    }

    private String executarConfirmacao(SessaoBot sessao) {
        return switch (sessao.getConfirmacaoPendente().getTipo()) {
            case "AGENDAR" -> executorAcaoService.executarAgendamento(
                    sessao, sessao.getConfirmacaoPendente().getPayload());
            case "CANCELAR" -> executorAcaoService.executarCancelamento(
                    sessao,
                    (String) sessao.getConfirmacaoPendente().getPayload().get("agendamentoId"));
            case "CADASTRAR" -> executarCadastro(sessao);
            default -> "Ação desconhecida.";
        };
    }

    private String executarCadastro(SessaoBot sessao) {
        try {
            var payload = sessao.getConfirmacaoPendente().getPayload();
            String email = (String) payload.get("email");

            // 1. Cria o paciente no sgsm (com JWT de sistema, pois o usuário não tem token ainda)
            var pacienteReq = new PacienteCreateRequest(
                    (String) payload.get("nomeCompleto"),
                    (String) payload.get("cpf"),
                    ExecutorAcaoService.dataIso((String) payload.get("dataNascimento")),
                    email,
                    sessao.getNumero(),
                    // Gate CONSENTIMENTO_CADASTRO no case CADASTRAR garante que este ponto só
                    // é alcançado após o usuário aceitar explicitamente (ver processar()).
                    sessao.isConsentimentoCadastroAceito()
            );
            var pacienteResp = sgsmClient.criarPaciente(pacienteReq, sistemaAuthService.token());
            String pacienteId = (String) pacienteResp.get("id");
            vincularAClinica(sessao, pacienteId);

            // 2. Registra no ms-sboot-auth
            String senhaTemp = UUID.randomUUID().toString().substring(0, 12);
            authClient.registrar(new AuthRegistrarRequest(email, senhaTemp, "PACIENTE", pacienteId));

            // 3. Inicia fluxo OTP para ativar e autenticar
            sessao.setConfirmacaoPendente(
                    new ConfirmacaoPendente("OTP", Map.of("email", email)));
            executorAcaoService.enviarOtp(sessao.getInstancia(), sessao.getNumero(), email);

            return "Cadastro criado! Para ativar sua conta, digite o código que acabei de enviar para este WhatsApp.";
        } catch (Exception e) {
            log.error("Erro no cadastro para {}: {}", sessao.getNumero(), e.getMessage());
            return "Erro ao realizar cadastro. Verifique se o CPF ou e-mail já estão cadastrados.";
        }
    }

    private void processarOtp(String numero, String otp, SessaoBot sessao) {
        try {
            String email = (String) sessao.getConfirmacaoPendente().getPayload().get("email");
            var resp = authClient.verificarOtp(email, otp.trim());
            sessao.setAccessToken((String) resp.get("accessToken"));
            sessao.setRefreshToken((String) resp.get("refreshToken"));
            sessao.setPerfil((String) resp.get("tipoPerfil"));
            try {
                var me = authClient.me(sessao.getAccessToken());
                Object referenciaId = me.get("referenciaId");
                sessao.setUsuarioId(referenciaId != null ? referenciaId.toString() : null);
            } catch (Exception e) {
                log.error("Erro ao buscar referenciaId em /auth/me para {}: {}", sessao.getNumero(), e.getMessage());
            }
            sessao.setConfirmacaoPendente(null);
            if ("PACIENTE".equals(sessao.getPerfil())) {
                vincularAClinica(sessao, sessao.getUsuarioId());
            }
            sessaoService.salvar(sessao);
            responder(sessao, "Conta ativada com sucesso! " + menuPrincipal(sessao));
        } catch (Exception e) {
            responder(sessao,
                    "Código incorreto ou expirado. Tente novamente.");
        }
    }

    private String menuPrincipal(SessaoBot sessao) {
        if (sessao.getAccessToken() == null) {
            return "Como posso ajudar?\n\n*1.* Agendar consulta\n*2.* Me cadastrar\n*3.* Informações sobre médicos\n\nOu me faça uma pergunta diretamente!";
        }
        return "Como posso ajudar?\n\n*1.* Ver meus agendamentos\n*2.* Agendar consulta\n*3.* Cancelar agendamento\n*4.* Falar com a IA\n\nOu me faça uma pergunta diretamente!";
    }

    private Map<String, Object> montarPayloadAgendamento(EntidadesExtraidas ent, SessaoBot sessao) {
        var payload = new HashMap<String, Object>();
        if (ent != null) {
            payload.put("medicoId", ent.getMedicoId());
            payload.put("servicoMedicoId", ent.getServicoMedicoId());
            payload.put("data", ent.getData());
            payload.put("hora", ent.getHora());
        }
        // ID do paciente vem da sessão autenticada; a clínica, do número (ou da escolha no canal geral)
        payload.put("pacienteId", sessao.getUsuarioId());
        payload.put("estabelecimentoId", sessao.getEstabelecimentoId());
        return payload;
    }

    private void atualizarDadosCadastro(SessaoBot sessao, EntidadesExtraidas ent) {
        if (sessao.getDadosCadastro() == null) {
            sessao.setDadosCadastro(new HashMap<>());
        }
        if (ent == null) return;
        if (ent.getNomeCompleto() != null) sessao.getDadosCadastro().put("nomeCompleto", ent.getNomeCompleto());
        if (ent.getCpf() != null) sessao.getDadosCadastro().put("cpf", ent.getCpf());
        if (ent.getDataNascimento() != null) sessao.getDadosCadastro().put("dataNascimento", ent.getDataNascimento());
        if (ent.getEmail() != null) sessao.getDadosCadastro().put("email", ent.getEmail());
    }

    private Map<String, Object> toObjectMap(Map<String, String> source) {
        if (source == null) return new HashMap<>();
        return new HashMap<>(source);
    }

    private String serializarConfirmacao(ConfirmacaoPendente cp) {
        try {
            return objectMapper.writeValueAsString(cp);
        } catch (Exception e) {
            log.warn("Falha ao serializar confirmação pendente: {}", e.getMessage());
            return null;
        }
    }
}
