package br.com.sgsm.whatsapp.dto;

import br.com.sgsm.whatsapp.domain.MensagemHistorico;

import java.util.List;

public record ClassificarRequest(
        String mensagem,
        List<MensagemHistorico> historico,
        String perfil,
        String confirmacaoPendente
) {}
