package br.com.sgsm.whatsapp.dto;

import br.com.sgsm.whatsapp.domain.IntentWhatsApp;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClassificarResponse {

    private IntentWhatsApp intent;
    private EntidadesExtraidas entidades;
    private String respostaUsuario;
    private boolean requerConfirmacao;
    private List<String> dadosFaltantes;
}
