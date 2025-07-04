package com.example.Programa_heber;

import com.example.Programa_heber.model.ExecucaoRequest;
import com.example.Programa_heber.model.PerguntaRequest;
import com.example.Programa_heber.model.ProcessamentoDetalhadoResposta;
import com.example.Programa_heber.service.QuestionProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Classe principal da aplicação Spring Boot.
 * Esta classe serve como o ponto de entrada para a aplicação e também como o
 * controlador REST que expõe os endpoints da API para a interface web.
 */
@SpringBootApplication
@RestController
@CrossOrigin(origins = "*") // Permite requisições de qualquer frontend.
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    private final QuestionProcessor questionProcessor;

    /**
     * Construtor para injeção de dependências via Spring.
     * @param questionProcessor O serviço que contém a lógica de negócio.
     */
    @Autowired
    public Main(QuestionProcessor questionProcessor) {
        this.questionProcessor = questionProcessor;
    }

    /**
     * Ponto de entrada principal que inicia a aplicação Spring Boot.
     */
    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
        logger.info(">>> Aplicação Natural2SPARQL iniciada e pronta para receber requisições. <<<");
    }

    /**
     * Endpoint para gerar a consulta SPARQL a partir de uma pergunta em linguagem natural.
     * Este é o primeiro passo do fluxo da interface.
     * 
     * @param request Um objeto JSON com a chave "pergunta". Ex: {"pergunta": "Qual o preço da PETR4?"}
     * @return Um ResponseEntity contendo a query gerada e o ID do template, ou um erro.
     */
    @PostMapping("/gerar_consulta")
    public ResponseEntity<ProcessamentoDetalhadoResposta> gerarConsulta(@RequestBody PerguntaRequest request) {
        logger.info("Recebida requisição para /gerar_consulta: '{}'", request.getPergunta());
        
        if (request.getPergunta() == null || request.getPergunta().isBlank()) {
            ProcessamentoDetalhadoResposta erro = new ProcessamentoDetalhadoResposta();
            erro.setErro("A pergunta não pode ser vazia.");
            return ResponseEntity.badRequest().body(erro);
        }

        // Chama o método específico para gerar a query no serviço
        ProcessamentoDetalhadoResposta resposta = questionProcessor.generateSparqlQuery(request.getPergunta());
        
        if (resposta.getErro() != null) {
            logger.error("Erro ao gerar consulta: {}", resposta.getErro());
            return ResponseEntity.internalServerError().body(resposta);
        }
        
        logger.info("Consulta gerada com sucesso para o template: {}", resposta.getTemplateId());
        return ResponseEntity.ok(resposta);
    }

    /**
     * Endpoint para executar uma consulta SPARQL que já foi gerada.
     * Este é o segundo passo do fluxo da interface, acionado pelo botão "Executar".
     * 
     * @param request Um objeto JSON com a query e o ID do template. Ex: {"sparqlQuery": "SELECT...", "templateId": "Template_1A"}
     * @return Um ResponseEntity contendo o resultado da consulta, ou um erro.
     */
    @PostMapping("/executar_query")
    public ResponseEntity<ProcessamentoDetalhadoResposta> executarConsulta(@RequestBody ExecucaoRequest request) {
        logger.info("Recebida requisição para /executar_query com templateId: {}", request.getTemplateId());

        if (request.getSparqlQuery() == null || request.getSparqlQuery().isBlank() || request.getTemplateId() == null) {
            ProcessamentoDetalhadoResposta erro = new ProcessamentoDetalhadoResposta();
            erro.setErro("A query SPARQL ou o ID do template não foram fornecidos.");
            return ResponseEntity.badRequest().body(erro);
        }
        
        // Chama o método específico para executar a query no serviço
        ProcessamentoDetalhadoResposta resposta = questionProcessor.executeSparqlQuery(request.getSparqlQuery(), request.getTemplateId());
        
         if (resposta.getErro() != null) {
            logger.error("Erro ao executar consulta: {}", resposta.getErro());
            return ResponseEntity.internalServerError().body(resposta);
        }

        logger.info("Consulta executada com sucesso. Resultado: {}", resposta.getResposta());
        return ResponseEntity.ok(resposta);
    }
}