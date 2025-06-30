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
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
            var resource = new ClassPathResource("pln_processor.py");
            if (!resource.exists()) {
                throw new FileNotFoundException("Script Python não encontrado no classpath: pln_processor.py");
            }

            Path tempDir = Files.createTempDirectory("pyscripts_temp_");
            this.pythonScriptPath = tempDir.resolve("pln_processor.py");
            try (InputStream inputStream = resource.getInputStream()) {
                Files.copy(inputStream, this.pythonScriptPath);
            }
            this.pythonScriptPath.toFile().setExecutable(true, false);
            this.pythonScriptPath.toFile().deleteOnExit();
            logger.info("Script Python extraído para path temporário executável: {}", this.pythonScriptPath);

        } catch (IOException e) {
            logger.error("CRÍTICO: Erro ao inicializar e preparar script Python: {}", e.getMessage(), e);
            this.pythonScriptPath = null;
        }
    }
    
    private Map<String, Object> executePythonScript(String question) throws IOException, InterruptedException {
        String pythonExec = System.getProperty("python.executable", "python3");
        
        ProcessBuilder pb = new ProcessBuilder(pythonExec, this.pythonScriptPath.toString(), question);
        pb.environment().put("PYTHONIOENCODING", "UTF-8");
        logger.info("Executando comando Python: {}", pb.command());
        Process process = pb.start();

        String stdoutResult;
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            stdoutResult = reader.lines().collect(Collectors.joining(System.lineSeparator()));
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String stderrResult;
            try (var reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                stderrResult = reader.lines().collect(Collectors.joining(System.lineSeparator()));
            }
            logger.error("Script Python falhou com código {}. Stderr: {}", exitCode, stderrResult);
            throw new IOException("Falha na execução do script Python. Veja os logs do servidor.");
        }
        
        logger.info("Saída (stdout) do script Python: {}", stdoutResult);
        return new ObjectMapper().readValue(stdoutResult, new TypeReference<>() {});
    }

    private String buildSparqlQuery(String templateContent, Map<String, String> placeholders) {
        String query = templateContent;
        if (placeholders == null) return query;

        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            query = query.replace(key, value);
        }
        return query;
    }

    public ProcessamentoDetalhadoResposta processQuestion(String question) {
        logger.info("Serviço QuestionProcessor: Iniciando processamento da pergunta: '{}'", question);
        ProcessamentoDetalhadoResposta respostaDetalhada = new ProcessamentoDetalhadoResposta();

        try {
            Map<String, Object> pythonResult = executePythonScript(question);
            
            if (pythonResult.containsKey("erro")) {
                String erroPython = (String) pythonResult.get("erro");
                logger.error("Script Python retornou um erro: {}", erroPython);
                respostaDetalhada.setErro("Falha no processamento da pergunta: " + erroPython);
                return respostaDetalhada;
            }

            String templateId = (String) pythonResult.get("template_nome");
            @SuppressWarnings("unchecked")
            Map<String, String> placeholders = (Map<String, String>) pythonResult.get("mapeamentos");

            String templateContent = readTemplateContent(templateId);
            String sparqlQuery = buildSparqlQuery(templateContent, placeholders);
            respostaDetalhada.setSparqlQuery(sparqlQuery);

            String targetVariable = "valor";
            if ("Template_2A".equals(templateId) || "Template_3A".equals(templateId)) {
                targetVariable = "ticker";
            }
            
            List<String> results = ontology.executeQuery(sparqlQuery, targetVariable);

            if (results == null) {
                respostaDetalhada.setErro("Ocorreu um erro ao executar a consulta na base de conhecimento.");
            } else if (results.isEmpty()) {
                respostaDetalhada.setResposta("Não foram encontrados resultados para a sua pergunta.");
            } else {
                respostaDetalhada.setResposta(String.join(", ", results));
            }
            
        } catch (Exception e) {
            logger.error("Erro geral no processamento da pergunta: {}", e.getMessage(), e);
            respostaDetalhada.setErro("Ocorreu um erro interno no servidor durante o processamento.");
        }

        return respostaDetalhada;
    }
    
    private String readTemplateContent(String templateId) throws IOException {
        String templateFileName = templateId + ".txt";
        String templateResourcePath = "Templates/" + templateFileName;
        Resource resource = new ClassPathResource(templateResourcePath);
        if (!resource.exists()) throw new FileNotFoundException("Template SPARQL não encontrado: " + templateResourcePath);
        
        try (InputStream inputStream = resource.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}