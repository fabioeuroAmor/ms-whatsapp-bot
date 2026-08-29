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
        Consentimento consentimento,
        ConsentimentoCadastro consentimentoCadastro
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

    public record Sistema(String jwt) {}

    public record Sessao(int ttlMinutos, int historicoMax) {}

    public record Consentimento(String mensagem) {}

    // LGPD 3.1 — consentimento específico do cadastro clínico (distinto do consentimento
    // de uso do canal WhatsApp acima, que é sobre a política de privacidade geral).
    public record ConsentimentoCadastro(String mensagem) {}
}
