package br.com.sgsm.whatsapp.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvolutionWebhookPayload {

    private String event;      // "messages.upsert"
    private String instance;
    private EvolutionData data;

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EvolutionData {
        private EvolutionKey key;
        private String pushName;
        private EvolutionMessage message;
        private String messageTimestamp;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EvolutionKey {
        private String remoteJid;   // "5511999999999@s.whatsapp.net"
        private Boolean fromMe;
        private String id;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EvolutionMessage {
        private String conversation;                           // texto simples
        private EvolutionExtendedText extendedTextMessage;    // texto com formatação/preview
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EvolutionExtendedText {
        private String text;
    }
}
