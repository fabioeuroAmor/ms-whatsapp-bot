package br.com.sgsm.whatsapp.client;

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

    public AuthClient(@Qualifier("authRestClient") RestClient authClient) {
        this.authClient = authClient;
    }

    public Map<String, Object> gerarOtp(String email) {
        return authClient.post()
                .uri("/v1/api/auth/otp/gerar")
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

    public Map<String, Object> login(String email, String senha) {
        return authClient.post()
                .uri("/v1/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "senha", senha))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public Map<String, Object> refresh(String refreshToken) {
        return authClient.post()
                .uri("/v1/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("refreshToken", refreshToken))
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
}
