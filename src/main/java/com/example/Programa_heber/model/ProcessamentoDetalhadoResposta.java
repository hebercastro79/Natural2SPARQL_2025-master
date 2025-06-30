package com.example.Programa_heber.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Esta classe é um DTO (Data Transfer Object) que encapsula a resposta completa
 * do processamento de uma pergunta. Ela é usada para comunicar o resultado
 * entre o backend e o frontend em formato JSON.
 * A anotação @JsonInclude(JsonInclude.Include.NON_NULL) garante que campos
 * com valor nulo (como 'erro' ou 'resposta') não sejam incluídos no JSON final,
 * tornando a resposta mais limpa.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProcessamentoDetalhadoResposta {

    private String sparqlQuery;
    private String resposta;
    private String erro;
    private String templateId; // Campo crucial para o fluxo de 2 etapas

    // Construtor padrão é necessário para a desserialização do Jackson/Spring
    public ProcessamentoDetalhadoResposta() {
    }

    // Getters e Setters para todos os campos

    public String getSparqlQuery() {
        return sparqlQuery;
    }

    public void setSparqlQuery(String sparqlQuery) {
        this.sparqlQuery = sparqlQuery;
    }

    public String getResposta() {
        return resposta;
    }

    public void setResposta(String resposta) {
        this.resposta = resposta;
    }

    public String getErro() {
        return erro;
    }

    public void setErro(String erro) {
        this.erro = erro;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }
}