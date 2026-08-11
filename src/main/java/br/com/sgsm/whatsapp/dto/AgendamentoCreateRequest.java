package br.com.sgsm.whatsapp.dto;

public record AgendamentoCreateRequest(
        String pacienteId,
        String servicoMedicoId,
        String dataHoraInicio
) {}
