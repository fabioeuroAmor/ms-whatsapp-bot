package br.com.sgsm.whatsapp.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmacaoPendente implements Serializable {

    private String tipo;               // AGENDAR | CANCELAR | CADASTRAR | OTP
    private Map<String, Object> payload;
}
