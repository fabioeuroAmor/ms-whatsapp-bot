package br.com.sgsm.whatsapp.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MensagemHistorico implements Serializable {

    private String papel;  // USUARIO | ASSISTENTE
    private String texto;
}
