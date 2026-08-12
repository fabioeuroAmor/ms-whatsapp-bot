package br.com.sgsm.whatsapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bot")
public record BotProperties(
        Evolution evolution,
        SgsmIa sgsmIa,
        Sgsm sgsm,
        Auth auth,
        Sistema sistema,
        Sessao sessao,
        Consentimento consentimento
) {

    public record Evolution(
            String baseUrl,
            String apiKey,
            String instance,
            String webhookSecret
    ) {}

    public record SgsmIa(String baseUrl) {}

    public record Sgsm(String baseUrl) {}

    public record Auth(String baseUrl) {}

    public record Sistema(String jwt, String email, String senha) {}

    public record Sessao(int ttlMinutos, int historicoMax) {}

    public record Consentimento(String mensagem) {}
}
