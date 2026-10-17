package br.com.sgsm.whatsapp.dto;

// Pré-reserva do horário no sgsm (POST /v1/api/agendamentos/reservas)
public record ReservaCreateRequest(
        String servicoMedicoId,
        String dataHoraInicio
) {}
