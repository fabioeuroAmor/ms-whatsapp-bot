package br.com.sgsm.whatsapp.controller;

import br.com.sgsm.whatsapp.notificacao.AgendaDiariaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Disparo manual dos jobs de notificação para testar sem esperar o horário do cron. Existe só
// em dev (@Profile) — não fica exposto em hom/prd, onde o bean nem é criado.
@RestController
@RequestMapping("/internal/notificacoes")
@Profile("dev")
@Tag(name = "Notificações (interno/dev)", description = "Disparo manual dos jobs proativos, só em dev")
public class NotificacaoInternalController {

    private final AgendaDiariaService agendaDiariaService;

    public NotificacaoInternalController(AgendaDiariaService agendaDiariaService) {
        this.agendaDiariaService = agendaDiariaService;
    }

    @PostMapping("/agenda-diaria/executar")
    @Operation(summary = "Dispara manualmente o job da agenda diária (Caso A), sem esperar 07:00")
    public ResponseEntity<Void> executarAgendaDiaria() {
        agendaDiariaService.enviarAgendaDoDia();
        return ResponseEntity.accepted().build();
    }
}
