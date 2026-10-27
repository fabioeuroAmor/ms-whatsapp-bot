package br.com.sgsm.whatsapp.service;

import br.com.sgsm.whatsapp.config.BotProperties;
import br.com.sgsm.whatsapp.domain.MensagemHistorico;
import br.com.sgsm.whatsapp.domain.SessaoBot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;

@Service
public class SessaoService {

    private static final Logger log = LoggerFactory.getLogger(SessaoService.class);
    private static final String SESSAO_PREFIX = "whatsapp:sessao:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final BotProperties props;

    public SessaoService(StringRedisTemplate redis,
                         ObjectMapper objectMapper,
                         BotProperties props) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    public SessaoBot obter(String numero) {
        return obter(numero, null);
    }

    // Uma conversa por número + clínica: o mesmo paciente falando com duas clínicas tem duas sessões
    // (instancia null = canal geral do SGSM, chave antiga)
    public SessaoBot obter(String numero, String instancia) {
        String json = redis.opsForValue().get(chave(numero, instancia));
        if (json == null) {
            return SessaoBot.builder()
                    .numero(numero)
                    .instancia(instancia)
                    .historico(new ArrayList<>())
                    .consentimentoAceito(false)
                    .build();
        }
        try {
            return objectMapper.readValue(json, SessaoBot.class);
        } catch (Exception e) {
            log.warn("Falha ao deserializar sessão de {}: {}", numero, e.getMessage());
            return SessaoBot.builder()
                    .numero(numero)
                    .instancia(instancia)
                    .historico(new ArrayList<>())
                    .consentimentoAceito(false)
                    .build();
        }
    }

    public void salvar(SessaoBot sessao) {
        try {
            String json = objectMapper.writeValueAsString(sessao);
            redis.opsForValue().set(
                    chave(sessao.getNumero(), sessao.getInstancia()),
                    json,
                    Duration.ofMinutes(props.sessao().ttlMinutos())
            );
        } catch (Exception e) {
            log.error("Falha ao salvar sessão de {}: {}", sessao.getNumero(), e.getMessage());
        }
    }

    static String chave(String numero, String instancia) {
        return instancia == null ? SESSAO_PREFIX + numero : SESSAO_PREFIX + instancia + ":" + numero;
    }

    // Marca do lead já criado para este número nesta clínica (não duplica entre sessões)
    private static final String LEAD_PREFIX = "whatsapp:lead:";
    private static final Duration TTL_LEAD = Duration.ofDays(180);

    public String leadDe(String estabelecimentoId, String numero) {
        return redis.opsForValue().get(LEAD_PREFIX + estabelecimentoId + ":" + numero);
    }

    public void guardarLead(String estabelecimentoId, String numero, String leadId) {
        redis.opsForValue().set(LEAD_PREFIX + estabelecimentoId + ":" + numero, leadId, TTL_LEAD);
    }

    public void adicionarHistorico(SessaoBot sessao, String papel, String texto) {
        if (sessao.getHistorico() == null) {
            sessao.setHistorico(new ArrayList<>());
        }
        sessao.getHistorico().add(new MensagemHistorico(papel, texto));

        // Mantém apenas as últimas N mensagens para não crescer indefinidamente
        int max = props.sessao().historicoMax();
        if (sessao.getHistorico().size() > max) {
            sessao.setHistorico(sessao.getHistorico().subList(
                    sessao.getHistorico().size() - max,
                    sessao.getHistorico().size()
            ));
        }
    }
}
