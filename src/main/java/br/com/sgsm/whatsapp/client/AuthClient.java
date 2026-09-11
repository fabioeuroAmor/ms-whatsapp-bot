package br.com.sgsm.whatsapp.client;

import br.com.sgsm.whatsapp.config.BotProperties;
import br.com.sgsm.whatsapp.dto.AuthRegistrarRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class AuthClient {

    private final RestClient authClient;
    private final BotProperties props;

    public AuthClient(@Qualifier("authRestClient") RestClient authClient, BotProperties props) {
        this.authClient = authClient;
        this.props = props;
    }

    // ms-sboot-auth só aceita POST /otp/gerar com o JWT de sistema (perfil DESENVOLVEDOR) —
    // o bot é o único autorizado a disparar OTP em nome de outro usuário.
    public Map<String, Object> gerarOtp(String email) {
        return authClient.post()
                .uri("/v1/api/auth/otp/gerar")
                .header("Authorization", "Bearer " + props.sistema().jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public Map<String, Object> verificarOtp(String email, String otp) {
        return authClient.post()
                .uri("/v1/api/auth/otp/verificar")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "otp", otp))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public Map<String, Object> registrar(AuthRegistrarRequest req) {
        return authClient.post()
                .uri("/v1/api/auth/registrar")
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public Map<String, Object> me(String accessToken) {
        return authClient.get()
                .uri("/v1/api/auth/me")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }
}
