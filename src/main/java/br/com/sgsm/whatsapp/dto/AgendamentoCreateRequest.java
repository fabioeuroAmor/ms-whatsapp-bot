package br.com.sgsm.whatsapp.dto;

// reservaToken: pré-reserva do horário feita antes de pedir a confirmação ao paciente (opcional).
// estabelecimentoId/tipo: clínica do atendimento (o agendamento já nasce nela)
public record AgendamentoCreateRequest(
        String pacienteId,
        String servicoMedicoId,
        String dataHoraInicio,
        String reservaToken,
        String estabelecimentoId,
        String tipo
) {
    public AgendamentoCreateRequest(String pacienteId, String servicoMedicoId, String dataHoraInicio, String reservaToken) {
        this(pacienteId, servicoMedicoId, dataHoraInicio, reservaToken, null, null);
    }
}
