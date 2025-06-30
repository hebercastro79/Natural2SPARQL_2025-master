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
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class QuestionProcessor {

    private static final Logger logger = LoggerFactory.getLogger(QuestionProcessor.class);

    @Autowired
    private Ontology ontology;

    private Path pythonScriptPath;
    private Path pythonResourcesDir;

    @PostConstruct
    public void initialize() {
        logger.info("Inicializando QuestionProcessor e preparando ambiente Python...");
        try {
            this.pythonResourcesDir = Files.createTempDirectory("pyscripts_");
            this.pythonResourcesDir.toFile().deleteOnExit();

            String[] resourcesToCopy = {
                "pln_processor.py",
                "empresa_nome_map.json",
                "setor_map.json",
                "resultado_similaridade.txt",
                "perguntas_de_interesse.txt"
            };

            for (String resourceName : resourcesToCopy) {
                copyResourceToTempDir(resourceName, this.pythonResourcesDir);
            }

            this.pythonScriptPath = this.pythonResourcesDir.resolve("pln_processor.py");
            boolean executable = this.pythonScriptPath.toFile().setExecutable(true, false);
            
            logger.info("Recursos Python extraídos para diretório temporário: {} (Script executável: {})", this.pythonResourcesDir, executable);

        } catch (IOException e) {
            logger.error("FALHA CRÍTICA na inicialização do QuestionProcessor. O processamento de perguntas estará indisponível.", e);
            this.pythonScriptPath = null;
        }
    }
    
    public ProcessamentoDetalhadoResposta generateSparqlQuery(String question) {
        ProcessamentoDetalhadoResposta resposta = new ProcessamentoDetalhadoResposta();
        if (this.pythonScriptPath == null) {
            resposta.setErro("Serviço de processamento de linguagem não está inicializado.");
            return resposta;
        }
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
            resposta.setTemplateId(templateId);
            
        } catch (Exception e) {
            logger.error("Erro ao gerar consulta SPARQL: {}", e.getMessage(), e);
            resposta.setErro("Erro interno ao gerar a consulta SPARQL.");
        }
        return resposta;
    }
    
    public ProcessamentoDetalhadoResposta executeSparqlQuery(String sparqlQuery, String templateId) {
        ProcessamentoDetalhadoResposta resposta = new ProcessamentoDetalhadoResposta();
        resposta.setSparqlQuery(sparqlQuery);

        try {
            String targetVariable = "valor";
            if ("Template_2A".equals(templateId) || "Template_3A".equals(templateId)) {
                targetVariable = "ticker";
            }
            
            List<String> results = ontology.executeQuery(sparqlQuery, targetVariable);

            if (results == null) {
                resposta.setErro("Erro na execução da consulta.");
            } else if (results.isEmpty()) {
                resposta.setResposta("Nenhum resultado encontrado.");
            } else {
                resposta.setResposta(String.join("\n", results)); // Usa quebra de linha para múltiplos resultados
            }
        } catch (Exception e) {
            logger.error("Erro ao executar a consulta SPARQL: {}", e.getMessage(), e);
            resposta.setErro("Erro interno ao executar a consulta.");
        }
        return resposta;
    }

    private void copyResourceToTempDir(String resourceName, Path targetDir) throws IOException {
        Resource resource = new ClassPathResource(resourceName);
        if (!resource.exists()) {
            throw new FileNotFoundException("Recurso Python essencial '" + resourceName + "' não encontrado no classpath.");
        }
        Path targetFile = targetDir.resolve(resourceName);
        try (InputStream in = resource.getInputStream()) {
            Files.copy(in, targetFile);
        }
    }

    private Map<String, Object> executePythonScript(String question) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("python3", this.pythonScriptPath.toAbsolutePath().toString(), question);
        pb.directory(this.pythonResourcesDir.toFile());
        
        logger.info("Executando comando: {}", String.join(" ", pb.command()));
        Process process = pb.start();

        String stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))
                .lines().collect(Collectors.joining("\n"));
        String stderr = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))
                .lines().collect(Collectors.joining("\n"));

        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new InterruptedException("Processo Python demorou mais de 60 segundos para responder.");
        }
        
        if (!stderr.isEmpty()) {
            logger.warn("Saída de erro do script Python (stderr):\n{}", stderr);
        }

        if (process.exitValue() != 0) {
            throw new IOException("Script Python falhou com código de saída " + process.exitValue() + ". Stderr: " + stderr);
        }

        if (stdout.isBlank()) {
            throw new IOException("Script Python não retornou nenhuma saída (stdout).");
        }
        
        logger.info("Saída (stdout) do script Python: {}", stdout);
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