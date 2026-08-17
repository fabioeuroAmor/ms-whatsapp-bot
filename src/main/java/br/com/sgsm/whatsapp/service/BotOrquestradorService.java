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
    private static final int LIMITE_TENTATIVAS_SEM_CPF = 2;

    private final SessaoService sessaoService;
    private final SgsmIaClient sgsmIaClient;
    private final EvolutionApiClient evolutionApiClient;
    private final ExecutorAcaoService executorAcaoService;
    private final AuthClient authClient;
    private final SgsmClient sgsmClient;
    private final BotProperties props;
    private final ObjectMapper objectMapper;

    public BotOrquestradorService(SessaoService sessaoService,
                                  SgsmIaClient sgsmIaClient,
                                  EvolutionApiClient evolutionApiClient,
                                  ExecutorAcaoService executorAcaoService,
                                  AuthClient authClient,
                                  SgsmClient sgsmClient,
                                  BotProperties props,
                                  ObjectMapper objectMapper) {
        this.sessaoService = sessaoService;
        this.sgsmIaClient = sgsmIaClient;
        this.evolutionApiClient = evolutionApiClient;
        this.executorAcaoService = executorAcaoService;
        this.authClient = authClient;
        this.sgsmClient = sgsmClient;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    public void processar(String numero, String texto, String nomeUsuario) {
        SessaoBot sessao = sessaoService.obter(numero);
        if (nomeUsuario != null && sessao.getNomeUsuario() == null) {
            sessao.setNomeUsuario(nomeUsuario);
        }

        // 1. Consentimento LGPD (primeiro contato)
        if (!sessao.isConsentimentoAceito()) {
            if ("ACEITO".equalsIgnoreCase(texto.trim())) {
                sessao.setConsentimentoAceito(true);
                sessaoService.salvar(sessao);
                evolutionApiClient.enviarTexto(numero, menuPrincipal(sessao));
            } else {
                evolutionApiClient.enviarTexto(numero, props.consentimento().mensagem());
                sessaoService.salvar(sessao);
            }
            return;
        }

        // 2. Se há OTP pendente, verificar
        if (sessao.getConfirmacaoPendente() != null
                && "OTP".equals(sessao.getConfirmacaoPendente().getTipo())) {
            processarOtp(numero, texto, sessao);
            return;
        }

        // 2.5 Se há confirmação pendente (AGENDAR/CANCELAR/CADASTRAR), resolve confirmação/recusa
        // em linguagem natural localmente, sem depender do classificador da IA acertar o intent CONFIRMAR/RECUSAR.
        if (sessao.getConfirmacaoPendente() != null) {
            String textoNormalizado = texto.trim().toLowerCase();
            if (pareceConfirmacao(textoNormalizado)) {
                String resultado = executarConfirmacao(sessao);
                sessao.setConfirmacaoPendente(null);
                sessaoService.salvar(sessao);
                evolutionApiClient.enviarTexto(numero, resultado);
                return;
            }
            if (pareceRecusa(textoNormalizado)) {
                sessao.setConfirmacaoPendente(null);
                sessaoService.salvar(sessao);
                evolutionApiClient.enviarTexto(numero, "Tudo bem! " + menuPrincipal(sessao));
                return;
            }
        }

        // 3. RAG: Classificar intenção via sgsm-ia
        var classificarReq = new ClassificarRequest(
                traduzirOpcaoMenu(texto.trim(), sessao),
                sessao.getHistorico() != null
                        ? sessao.getHistorico().stream()
                                .map(m -> new MensagemHistorico(m.getPapel(), m.getTexto()))
                                .toList()
                        : List.of(),
                sessao.getPerfil(),
                sessao.getConfirmacaoPendente() != null
                        ? serializarConfirmacao(sessao.getConfirmacaoPendente())
                        : null
        );

        ClassificarResponse resp = sgsmIaClient.classificar(classificarReq, sessao.getAccessToken());
        if (resp == null) {
            evolutionApiClient.enviarTexto(numero,
                    "Serviço temporariamente indisponível. Tente novamente em alguns instantes.");
            return;
        }

        log.debug("Intent detectado para {}: {}", numero, resp.getIntent());

        // 4. Atualiza histórico
        sessaoService.adicionarHistorico(sessao, "USUARIO", texto);
        if (resp.getRespostaUsuario() != null) {
            sessaoService.adicionarHistorico(sessao, "ASSISTENTE", resp.getRespostaUsuario());
        }

        // 4.5 Circuit-breaker: evita ficar repetindo o pedido de CPF indefinidamente
        if (cadastroPendenteSemCpf(sessao)) {
            String cpfInformado = resp.getEntidades() != null ? resp.getEntidades().getCpf() : null;
            if (cpfInformado == null || cpfInformado.isBlank()) {
                sessao.setTentativasSemCpf(sessao.getTentativasSemCpf() + 1);
                if (sessao.getTentativasSemCpf() >= LIMITE_TENTATIVAS_SEM_CPF) {
                    sessao.setDadosCadastro(new HashMap<>());
                    sessao.setConfirmacaoPendente(null);
                    sessao.setTentativasSemCpf(0);
                    sessaoService.salvar(sessao);
                    evolutionApiClient.enviarTexto(numero,
                            "Sem o CPF não consigo concluir o cadastro por aqui. "
                                    + "Entre em contato com nossa central de atendimento para prosseguir.\n\n"
                                    + menuPrincipal(sessao));
                    return;
                }
            } else {
                sessao.setTentativasSemCpf(0);
            }
        }

        // 5. Executa ação baseada no intent
        String respostaFinal = switch (resp.getIntent()) {
            case CONSULTAR, CONVERSAR -> resp.getRespostaUsuario();

            case AUTENTICAR -> {
                if (sessao.getAccessToken() == null) {
                    yield iniciarAutenticacaoOuPedirEmail(sessao, resp, resp.getRespostaUsuario()
                            + "\n\n_Digite seu e-mail cadastrado para receber um código de verificação._");
                }
                yield "Você já está autenticado como " + sessao.getPerfil() + ".";
            }

            case AGENDAR -> {
                if (sessao.getAccessToken() == null) {
                    yield iniciarAutenticacaoOuPedirEmail(sessao, resp,
                            "Para agendar, primeiro preciso confirmar sua identidade.\n_Digite seu e-mail cadastrado:_");
                }
                if (!resp.getDadosFaltantes().isEmpty() || !resp.isRequerConfirmacao()) {
                    yield resp.getRespostaUsuario();
                }
                // Todos os dados presentes: salva confirmação pendente
                var payload = montarPayloadAgendamento(resp.getEntidades(), sessao);
                sessao.setConfirmacaoPendente(new ConfirmacaoPendente("AGENDAR", payload));
                yield resp.getRespostaUsuario();
            }

            case CANCELAR -> {
                if (sessao.getAccessToken() == null) {
                    yield iniciarAutenticacaoOuPedirEmail(sessao, resp,
                            "Para cancelar, primeiro preciso confirmar sua identidade.\n_Digite seu e-mail cadastrado:_");
                }
                if (resp.getEntidades() != null && resp.getEntidades().getAgendamentoId() != null) {
                    sessao.setConfirmacaoPendente(new ConfirmacaoPendente("CANCELAR",
                            Map.of("agendamentoId", resp.getEntidades().getAgendamentoId())));
                }
                yield resp.getRespostaUsuario();
            }

            case CADASTRAR -> {
                // Coleta dados progressivamente turno a turno
                atualizarDadosCadastro(sessao, resp.getEntidades());
                if (resp.isRequerConfirmacao()) {
                    sessao.setConfirmacaoPendente(new ConfirmacaoPendente("CADASTRAR",
                            new HashMap<>(toObjectMap(sessao.getDadosCadastro()))));
                    yield resp.getRespostaUsuario() + "\n\n_Responda *SIM* para confirmar o cadastro._";
                }
                yield resp.getRespostaUsuario();
            }

            case ATIVAR_FUNC -> resp.getRespostaUsuario();

            case CONFIRMAR -> {
                if (sessao.getConfirmacaoPendente() == null) {
                    yield "Não há nenhuma ação pendente para confirmar.";
                }
                String resultado = executarConfirmacao(sessao);
                sessao.setConfirmacaoPendente(null);
                yield resultado;
            }

            case RECUSAR -> {
                sessao.setConfirmacaoPendente(null);
                yield "Tudo bem! " + menuPrincipal(sessao);
            }
        };

        sessaoService.salvar(sessao);
        evolutionApiClient.enviarTexto(numero, respostaFinal != null ? respostaFinal : "...");
    }

    // --- Métodos privados de suporte ---

    private boolean pareceConfirmacao(String textoNormalizado) {
        return textoNormalizado.equals("sim") || textoNormalizado.equals("ok")
                || textoNormalizado.startsWith("sim")
                || textoNormalizado.contains("confirm")
                || textoNormalizado.contains("pode confirmar")
                || textoNormalizado.contains("pode prosseguir")
                || textoNormalizado.contains("isso mesmo")
                || textoNormalizado.equals("correto");
    }

    private boolean pareceRecusa(String textoNormalizado) {
        return textoNormalizado.equals("não") || textoNormalizado.equals("nao")
                || textoNormalizado.startsWith("não") || textoNormalizado.startsWith("nao")
                || textoNormalizado.contains("cancel")
                || textoNormalizado.contains("recus");
    }

    // Traduz opções numéricas do menu (1-4) para linguagem natural, já que o classificador
    // da IA não tem contexto de qual menu foi exibido e não interpreta dígitos soltos de forma confiável.
    private String traduzirOpcaoMenu(String texto, SessaoBot sessao) {
        boolean autenticado = sessao.getAccessToken() != null;
        String traduzido = switch (texto) {
            case "1" -> autenticado ? "Quero ver meus agendamentos" : "Quero agendar uma consulta";
            case "2" -> autenticado ? "Quero agendar uma consulta" : "Quero me cadastrar";
            case "3" -> autenticado ? "Quero cancelar um agendamento" : "Quero informações sobre os médicos";
            case "4" -> autenticado ? "Quero falar com a IA/assistente" : null;
            default -> null;
        };
        return traduzido != null ? traduzido : texto;
    }

    private boolean cadastroPendenteSemCpf(SessaoBot sessao) {
        Map<String, String> dados = sessao.getDadosCadastro();
        return dados != null && dados.containsKey("nomeCompleto") && !dados.containsKey("cpf");
    }

    private String iniciarAutenticacaoOuPedirEmail(SessaoBot sessao, ClassificarResponse resp, String mensagemPedidoEmail) {
        String email = resp.getEntidades() != null ? resp.getEntidades().getEmail() : null;
        if (email == null || email.isBlank()) {
            return mensagemPedidoEmail;
        }
        if (!executorAcaoService.enviarOtp(sessao.getNumero(), email)) {
            return "Não encontrei nenhum cadastro com o e-mail *" + email
                    + "*. Verifique se digitou corretamente ou digite *2* no menu para se cadastrar.";
        }
        sessao.setConfirmacaoPendente(new ConfirmacaoPendente("OTP", Map.of("email", email)));
        return "Enviei um código de verificação para o seu WhatsApp.\n_Digite o código que você recebeu:_";
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
                    (String) payload.get("dataNascimento"),
                    email,
                    sessao.getNumero()
            );
            var pacienteResp = sgsmClient.criarPaciente(pacienteReq, props.sistema().jwt());
            String pacienteId = (String) pacienteResp.get("id");

            // 2. Registra no ms-sboot-auth
            String senhaTemp = UUID.randomUUID().toString().substring(0, 12);
            authClient.registrar(new AuthRegistrarRequest(email, senhaTemp, "PACIENTE", pacienteId));

            // 3. Inicia fluxo OTP para ativar e autenticar
            sessao.setConfirmacaoPendente(
                    new ConfirmacaoPendente("OTP", Map.of("email", email)));
            boolean otpEnviado = executorAcaoService.enviarOtp(sessao.getNumero(), email);

            return otpEnviado
                    ? "Cadastro criado! Para ativar sua conta, digite o código que acabei de enviar para este WhatsApp."
                    : "Cadastro criado, mas houve um problema ao enviar o código de verificação agora. Tente novamente em alguns instantes.";
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
            sessao.setConfirmacaoPendente(null);
            sessaoService.salvar(sessao);
            evolutionApiClient.enviarTexto(numero, "Conta ativada com sucesso! " + menuPrincipal(sessao));
        } catch (Exception e) {
            evolutionApiClient.enviarTexto(numero,
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
        // ID do paciente vem da sessão autenticada
        payload.put("pacienteId", sessao.getUsuarioId());
        return payload;
    }

    private void atualizarDadosCadastro(SessaoBot sessao, EntidadesExtraidas ent) {
        if (sessao.getDadosCadastro() == null) {
            sessao.setDadosCadastro(new HashMap<>());
        }
        if (ent == null) return;

        // Se um nome diferente do já coletado aparece, é outra pessoa: descarta os dados
        // antigos em vez de misturar (ex.: CPF de uma pessoa com nome de outra).
        String nomeExistente = sessao.getDadosCadastro().get("nomeCompleto");
        if (ent.getNomeCompleto() != null && nomeExistente != null
                && !nomeExistente.equalsIgnoreCase(ent.getNomeCompleto())) {
            sessao.getDadosCadastro().clear();
        }

        if (ent.getNomeCompleto() != null) sessao.getDadosCadastro().put("nomeCompleto", ent.getNomeCompleto());
        // Ignora CPF mascarado/redigido (ex.: "***.***.***-**") vindo da IA: não é um valor utilizável.
        if (ent.getCpf() != null && !ent.getCpf().contains("*")) sessao.getDadosCadastro().put("cpf", ent.getCpf());
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
