package com.example.Programa_heber.service;

import com.example.Programa_heber.model.ProcessamentoDetalhadoResposta;
import com.example.Programa_heber.ontology.Ontology;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource; // Import explícito para o Resource do Spring
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class QuestionProcessor {

    private static final Logger logger = LoggerFactory.getLogger(QuestionProcessor.class);

    @Autowired
    private Ontology ontology;
    private Path pythonScriptPath;

    @PostConstruct
    public void initialize() {
        logger.info("Iniciando QuestionProcessor (@PostConstruct)...");
        try {
            Resource resource = new ClassPathResource("pln_processor.py");
            if (!resource.exists()) {
                throw new FileNotFoundException("Script Python não encontrado no classpath: pln_processor.py");
            }
            Path tempDir = Files.createTempDirectory("pyscripts_");
            this.pythonScriptPath = tempDir.resolve("pln_processor.py");
            try (InputStream inputStream = resource.getInputStream()) {
                Files.copy(inputStream, this.pythonScriptPath);
            }
            boolean executable = this.pythonScriptPath.toFile().setExecutable(true, false);
            logger.info("Script Python extraído para path temporário: {} (Executável: {})", this.pythonScriptPath, executable);
            this.pythonScriptPath.toFile().deleteOnExit();
        } catch (IOException e) {
            logger.error("CRÍTICO: Erro ao inicializar e preparar script Python: {}", e.getMessage(), e);
        }
    }
    
    public ProcessamentoDetalhadoResposta processQuestion(String question) {
        ProcessamentoDetalhadoResposta resposta = new ProcessamentoDetalhadoResposta();
        try {
            Map<String, Object> pyResult = executePythonScript(question);
            if (pyResult.containsKey("erro")) {
                resposta.setErro((String) pyResult.get("erro"));
                return resposta;
            }

            String templateId = (String) pyResult.get("template_nome");
            @SuppressWarnings("unchecked")
            Map<String, String> placeholders = (Map<String, String>) pyResult.get("mapeamentos");

            String templateContent = readTemplateContent(templateId);
            String sparqlQuery = buildSparqlQuery(templateContent, placeholders);
            resposta.setSparqlQuery(sparqlQuery);

            String targetVariable;
            if ("Template_2A".equals(templateId) || "Template_3A".equals(templateId)) {
                targetVariable = "ticker";
            } else {
                targetVariable = "valor";
            }
            
            List<String> results = ontology.executeQuery(sparqlQuery, targetVariable);

            if (results == null) {
                resposta.setErro("Erro ao executar a consulta na base de conhecimento.");
            } else if (results.isEmpty()) {
                resposta.setResposta("Não foram encontrados resultados para a sua pergunta.");
            } else {
                resposta.setResposta(String.join(", ", results));
            }
        } catch (Exception e) {
            logger.error("Erro geral no processamento da pergunta: {}", e.getMessage(), e);
            resposta.setErro("Ocorreu um erro interno no servidor durante o processamento.");
        }
        return resposta;
    }

    private Map<String, Object> executePythonScript(String question) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("python3", this.pythonScriptPath.toString(), question);
        pb.environment().put("PYTHONIOENCODING", "UTF-8");
        Process process = pb.start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            logger.error("Script Python falhou com código {}. Stderr: {}", exitCode, stderr);
            throw new IOException("Falha na execução do script Python: " + stderr);
        }
        logger.info("Saída (stdout) do script Python: {}", stdout);
        if(stdout.isBlank()){
             throw new IOException("Script Python retornou uma saída vazia.");
        }
        return new ObjectMapper().readValue(stdout, new TypeReference<>() {});
    }

    private String buildSparqlQuery(String templateContent, Map<String, String> placeholders) {
        String query = templateContent;
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                query = query.replace(entry.getKey(), entry.getValue());
            }
        }
        return query;
    }

    private String readTemplateContent(String templateId) throws IOException {
        String path = "Templates/" + templateId + ".txt";
        Resource resource = new ClassPathResource(path);
        if (!resource.exists()) throw new FileNotFoundException("Template SPARQL não encontrado: " + path);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}