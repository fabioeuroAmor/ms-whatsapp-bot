package br.com.sgsm.whatsapp.service;

import br.com.sgsm.whatsapp.client.SgsmClient;
import br.com.sgsm.whatsapp.config.BotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// WhatsApp por clínica: de qual clínica é a instância (número) que recebeu a mensagem. A clínica vem
// SEMPRE daqui (cadastro do admin no sgsm), nunca do texto da conversa. Cache curto para não consultar
// o sgsm a cada mensagem; instância desconhecida também fica em cache (e a mensagem é ignorada).
@Service
public class CanalService {

    private static final Logger log = LoggerFactory.getLogger(CanalService.class);
    static final long VALIDADE_CACHE_SEGUNDOS = 60;

    // instancia null = canal geral do SGSM (número único usado antes do WhatsApp por clínica)
    public record Canal(String instancia, String estabelecimentoId, String estabelecimentoNome) {
        public boolean geral() { return instancia == null; }
    }

    private record Entrada(Optional<Canal> canal, Instant expiraEm) {}

    private final SgsmClient sgsmClient;
    private final SistemaAuthService sistemaAuthService;
    private final BotProperties props;
    private final Clock clock;
    private final Map<String, Entrada> cache = new ConcurrentHashMap<>();

    @Autowired
    public CanalService(SgsmClient sgsmClient, SistemaAuthService sistemaAuthService, BotProperties props) {
        this(sgsmClient, sistemaAuthService, props, Clock.systemUTC());
    }

    CanalService(SgsmClient sgsmClient, SistemaAuthService sistemaAuthService, BotProperties props, Clock clock) {
        this.sgsmClient = sgsmClient;
        this.sistemaAuthService = sistemaAuthService;
        this.props = props;
        this.clock = clock;
    }

    public Optional<Canal> resolver(String instancia) {
        if (instancia == null || instancia.isBlank() || instancia.equals(props.evolution().instance())) {
            return Optional.of(new Canal(null, null, null));
        }
        var emCache = cache.get(instancia);
        if (emCache != null && emCache.expiraEm().isAfter(clock.instant())) {
            return emCache.canal();
        }
        Optional<Canal> canal;
        try {
            Map<String, Object> resposta = sgsmClient.resolverCanal(instancia, sistemaAuthService.token());
            canal = resposta == null || resposta.get("estabelecimentoId") == null
                    ? Optional.empty()
                    : Optional.of(new Canal(instancia, resposta.get("estabelecimentoId").toString(),
                            resposta.get("estabelecimentoNome") == null ? null : resposta.get("estabelecimentoNome").toString()));
        } catch (HttpClientErrorException.NotFound e) {
            canal = Optional.empty();
        } catch (RuntimeException e) {
            // sgsm fora do ar: não guarda em cache, tenta de novo na próxima mensagem
            log.warn("Não foi possível identificar a clínica da instância {}: {}", instancia, e.getMessage());
            return Optional.empty();
        }
        if (canal.isEmpty()) {
            log.warn("Mensagem recebida por instância de WhatsApp desconhecida ou removida: {}", instancia);
        }
        cache.put(instancia, new Entrada(canal, clock.instant().plusSeconds(VALIDADE_CACHE_SEGUNDOS)));
        return canal;
    }
}
