package br.com.sgsm.whatsapp.dto;

// Pré-reserva do horário no sgsm (POST /v1/api/agendamentos/reservas). estabelecimentoId/tipo: clínica
// do WhatsApp que recebeu a conversa (PRESENCIAL nela); nulos no canal geral sem clínica escolhida
public record ReservaCreateRequest(
        String servicoMedicoId,
        String dataHoraInicio,
        String estabelecimentoId,
        String tipo
) {
    public ReservaCreateRequest(String servicoMedicoId, String dataHoraInicio) {
        this(servicoMedicoId, dataHoraInicio, null, null);
    }
}
