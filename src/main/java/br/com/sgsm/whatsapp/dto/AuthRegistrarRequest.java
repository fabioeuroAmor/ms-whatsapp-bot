package br.com.sgsm.whatsapp.dto;

public record AuthRegistrarRequest(
        String email,
        String senha,
        String tipoPerfil,
        String referenciaId
) {}
