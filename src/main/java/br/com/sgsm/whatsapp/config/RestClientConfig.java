package br.com.sgsm.whatsapp.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    private final BotProperties props;

    public RestClientConfig(BotProperties props) {
        this.props = props;
    }

    private static SimpleClientHttpRequestFactory factory(Duration connectTimeout, Duration readTimeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    @Bean
    @Qualifier("evolutionClient")
    public RestClient evolutionClient() {
        return RestClient.builder()
                .baseUrl(props.evolution().baseUrl())
                .defaultHeader("apikey", props.evolution().apiKey())
                .build();
    }

    @Bean
    @Qualifier("sgsmIaRestClient")
    public RestClient sgsmIaRestClient() {
        // Timeout de 45s: o LLM pode demorar mas precisa ser responsivo para WhatsApp
        return RestClient.builder()
                .baseUrl(props.sgsmIa().baseUrl())
                .requestFactory(factory(Duration.ofSeconds(5), Duration.ofSeconds(45)))
                .build();
    }

    @Bean
    @Qualifier("sgsmRestClient")
    public RestClient sgsmRestClient() {
        return RestClient.builder()
                .baseUrl(props.sgsm().baseUrl())
                .requestFactory(factory(Duration.ofSeconds(5), Duration.ofSeconds(15)))
                .build();
    }

    @Bean
    @Qualifier("authRestClient")
    public RestClient authRestClient() {
        return RestClient.builder()
                .baseUrl(props.auth().baseUrl())
                .requestFactory(factory(Duration.ofSeconds(5), Duration.ofSeconds(10)))
                .build();
    }
}
