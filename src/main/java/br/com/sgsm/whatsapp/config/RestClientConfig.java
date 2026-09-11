package br.com.sgsm.whatsapp.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    private final BotProperties props;
    private final ObservationRegistry observationRegistry;

    public RestClientConfig(BotProperties props, ObservationRegistry observationRegistry) {
        this.props = props;
        this.observationRegistry = observationRegistry;
    }

    private static SimpleClientHttpRequestFactory factory(Duration connectTimeout, Duration readTimeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    // observationRegistry ligado explicitamente em cada RestClient: sem isso, as chamadas
    // entre servicos nao propagam o traceparent nem aparecem no Zipkin - o auto-configurado
    // RestClient.Builder do Spring nao se materializou aqui.
    private RestClient.Builder builder() {
        return RestClient.builder().observationRegistry(observationRegistry);
    }

    @Bean
    @Qualifier("evolutionClient")
    public RestClient evolutionClient() {
        return builder()
                .baseUrl(props.evolution().baseUrl())
                .defaultHeader("apikey", props.evolution().apiKey())
                .build();
    }

    @Bean
    @Qualifier("sgsmIaRestClient")
    public RestClient sgsmIaRestClient() {
        // Timeout de 45s: o LLM pode demorar mas precisa ser responsivo para WhatsApp
        return builder()
                .baseUrl(props.sgsmIa().baseUrl())
                .requestFactory(factory(Duration.ofSeconds(5), Duration.ofSeconds(45)))
                .build();
    }

    @Bean
    @Qualifier("sgsmRestClient")
    public RestClient sgsmRestClient() {
        return builder()
                .baseUrl(props.sgsm().baseUrl())
                .requestFactory(factory(Duration.ofSeconds(5), Duration.ofSeconds(15)))
                .build();
    }

    @Bean
    @Qualifier("authRestClient")
    public RestClient authRestClient() {
        return builder()
                .baseUrl(props.auth().baseUrl())
                .requestFactory(factory(Duration.ofSeconds(5), Duration.ofSeconds(10)))
                .build();
    }
}
