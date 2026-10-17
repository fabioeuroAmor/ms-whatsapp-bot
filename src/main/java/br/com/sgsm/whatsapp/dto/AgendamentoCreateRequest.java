package br.com.sgsm.whatsapp.dto;

// reservaToken: pré-reserva do horário feita antes de pedir a confirmação ao paciente (opcional)
public record AgendamentoCreateRequest(
        String pacienteId,
        String servicoMedicoId,
        String dataHoraInicio,
        String reservaToken
) {}
