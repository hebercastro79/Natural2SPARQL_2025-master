package com.example.Programa_heber.model;

public class ExecucaoRequest {
    private String sparqlQuery;
    private String templateId;

    // Getters e Setters
    public String getSparqlQuery() { return sparqlQuery; }
    public void setSparqlQuery(String sparqlQuery) { this.sparqlQuery = sparqlQuery; }
    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }
}