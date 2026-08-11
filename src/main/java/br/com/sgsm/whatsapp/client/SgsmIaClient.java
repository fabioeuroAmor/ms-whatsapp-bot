package br.com.sgsm.whatsapp.client;

import br.com.sgsm.whatsapp.config.BotProperties;
import br.com.sgsm.whatsapp.dto.ClassificarRequest;
import br.com.sgsm.whatsapp.dto.ClassificarResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SgsmIaClient {

    private static final Logger log = LoggerFactory.getLogger(SgsmIaClient.class);

    private final RestClient sgsmIaClient;
    private final BotProperties props;

    public SgsmIaClient(@Qualifier("sgsmIaRestClient") RestClient sgsmIaClient,
                        BotProperties props) {
        this.sgsmIaClient = sgsmIaClient;
        this.props = props;
    }

    public ClassificarResponse classificar(ClassificarRequest request, String accessToken) {
        String token = accessToken != null ? accessToken : props.sistema().jwt();
        return sgsmIaClient.post()
                .uri("/ia/whatsapp/classificar")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ClassificarResponse.class);
    }
}
