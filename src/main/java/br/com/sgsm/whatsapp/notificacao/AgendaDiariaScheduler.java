package br.com.sgsm.whatsapp.notificacao;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AgendaDiariaScheduler {

    private final AgendaDiariaService agendaDiariaService;

    public AgendaDiariaScheduler(AgendaDiariaService agendaDiariaService) {
        this.agendaDiariaService = agendaDiariaService;
    }

    // zone explicito: sem isso o cron roda no fuso da JVM/host, que pode virar UTC num
    // container/VPS e disparar as 07:00 UTC (04:00 em Brasília) sem ninguém notar.
    @Scheduled(cron = "${notificacao.agenda-diaria.cron:0 0 7 * * *}", zone = "America/Sao_Paulo")
    public void executar() {
        agendaDiariaService.enviarAgendaDoDia();
    }
}
