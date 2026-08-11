package br.com.sgsm.whatsapp;

import br.com.sgsm.whatsapp.config.BotProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(BotProperties.class)
public class MsWhatsappBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsWhatsappBotApplication.class, args);
    }
}
