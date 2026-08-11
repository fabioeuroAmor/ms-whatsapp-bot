package br.com.sgsm.whatsapp.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SessaoBot implements Serializable {

    private String numero;              // "5511999999999"
    private String usuarioId;           // UUID após autenticação
    private String perfil;              // PACIENTE | MEDICO | FUNCIONARIO | null
    private String accessToken;         // JWT atual
    private String refreshToken;
    private String nomeUsuario;

    @Builder.Default
    private boolean consentimentoAceito = false;

    @Builder.Default
    private List<MensagemHistorico> historico = new ArrayList<>();

    private ConfirmacaoPendente confirmacaoPendente;

    @Builder.Default
    private Map<String, String> dadosCadastro = new HashMap<>();
}
