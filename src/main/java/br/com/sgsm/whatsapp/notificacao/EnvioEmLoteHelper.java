package br.com.sgsm.whatsapp.notificacao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

// EvolutionApiClient nao tem rate limiting proprio - ao mandar N mensagens (uma por medico,
// uma por paciente) em sequencia, este helper garante um espacamento minimo entre chamadas e
// isola cada item em try/catch, para que a falha de um destinatario nao interrompa os demais.
@Component
public class EnvioEmLoteHelper {

    private static final Logger log = LoggerFactory.getLogger(EnvioEmLoteHelper.class);
    private static final long INTERVALO_MS = 400;

    public <T> void executarComEspacamento(List<T> itens, Consumer<T> acao, Function<T, String> identificador) {
        for (T item : itens) {
            try {
                acao.accept(item);
            } catch (Exception e) {
                log.error("Falha ao processar item {}: {}", identificador.apply(item), e.getMessage(), e);
            }
            try {
                Thread.sleep(INTERVALO_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
