package br.com.sgsm.whatsapp.dto;

import br.com.sgsm.whatsapp.domain.MensagemHistorico;

import java.util.List;

// estabelecimentoId/estabelecimentoNome: clínica do atendimento (sgsm-ia só oferece o que é dela)
public record ClassificarRequest(
        String mensagem,
        List<MensagemHistorico> historico,
        String perfil,
        String confirmacaoPendente,
        String estabelecimentoId,
        String estabelecimentoNome
) {
    public ClassificarRequest(String mensagem, List<MensagemHistorico> historico, String perfil,
                              String confirmacaoPendente) {
        this(mensagem, historico, perfil, confirmacaoPendente, null, null);
    }
}
