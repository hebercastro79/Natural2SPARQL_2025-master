package com.example.Programa_heber.ontology;

import jakarta.annotation.PostConstruct;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.reasoner.Reasoner;
import org.apache.jena.reasoner.ReasonerRegistry;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.shared.JenaException;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

@Component
public class Ontology {

    private static final Logger logger = LoggerFactory.getLogger(Ontology.class);

    private Model baseModel;
    private InfModel infModel;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private static final String ONT_PREFIX = "https://dcm.ffclrp.usp.br/lssb/stock-market-ontology#";
    private static final String[] PREGAO_FILES = { "Datasets/dados_novos_anterior.xlsx", "Datasets/dados_novos_atual.xlsx" };
    private static final String INFO_EMPRESAS_FILE = "Templates/Informacoes_Empresas.xlsx";
    private static final String ONTOLOGY_FILE = "ontologiaB3.ttl";
    private static final String INFERENCE_OUTPUT_FILE = "ontologiaB3_com_inferencia.ttl";

    @PostConstruct
    public void init() {
        logger.info(">>> INICIANDO Inicialização da Ontologia (@PostConstruct)...");
        lock.writeLock().lock();
        try {
            baseModel = ModelFactory.createDefaultModel();
            baseModel.setNsPrefix("b3", ONT_PREFIX);
            baseModel.setNsPrefix("rdf", RDF.uri);
            baseModel.setNsPrefix("rdfs", RDFS.uri);
            baseModel.setNsPrefix("xsd", XSDDatatype.XSD + "#");

            loadRdfData(ONTOLOGY_FILE, Lang.TURTLE, "Esquema base da Ontologia");
            loadInformacoesEmpresas(INFO_EMPRESAS_FILE);
            for (String filePath : PREGAO_FILES) {
                loadDadosPregaoExcel(filePath);
            }

            logger.info("Total de triplas no modelo base antes da inferência: {}", baseModel.size());
            
            logger.info("--- Configurando Reasoner e criando modelo de inferência ---");
            Reasoner reasoner = ReasonerRegistry.getRDFSReasoner();
            infModel = ModelFactory.createInfModel(reasoner, baseModel);
            logger.info("--- Modelo de inferência criado. Total de triplas (base + inferidas): {} ---", infModel.size());

            saveInferredModel();
            logger.info("<<< ONTOLOGIA INICIALIZADA COM SUCESSO >>>");

        } catch (Exception e) {
            logger.error("!!!!!!!! FALHA GRAVE NA INICIALIZAÇÃO DA ONTOLOGY !!!!!!!!", e);
            baseModel = null; infModel = null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void loadInformacoesEmpresas(String resourcePath) throws IOException {
        logger.info(">> Carregando Informações de Empresas de: {}", resourcePath);
        int rowsProcessed = 0; int errors = 0;
        
        Resource resourceFile = new ClassPathResource(resourcePath);
        if (!resourceFile.exists()) throw new FileNotFoundException("Arquivo de empresas não encontrado: " + resourcePath);

        try (InputStream excelFile = resourceFile.getInputStream(); Workbook workbook = new XSSFWorkbook(excelFile)) {
            Sheet sheet = workbook.getSheetAt(0);
            
            final int nomeEmpresaColIdx = 0; // Col A
            final int codigoNegociacaoColIdx = 1; // Col B
            final int setorAtuacaoPrincipalColIdx = 5; // Col F

            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue; 

                String nomeEmpresaPlanilha = getStringCellValue(row.getCell(nomeEmpresaColIdx));
                String tickerPlanilha = getStringCellValue(row.getCell(codigoNegociacaoColIdx));
                String setorPrincipalPlanilha = getStringCellValue(row.getCell(setorAtuacaoPrincipalColIdx));

                if (nomeEmpresaPlanilha == null || nomeEmpresaPlanilha.isBlank() || tickerPlanilha == null || tickerPlanilha.isBlank()) {
                    errors++; continue;
                }
                
                try {
                    String nomeEmpresaNormalizado = normalizarTextoJava(nomeEmpresaPlanilha);
                    Resource empresaResource = baseModel.createResource(ONT_PREFIX + "Empresa_" + nomeEmpresaNormalizado);
                    addPropertyIfNotExist(empresaResource, RDF.type, getResource("Empresa_Capital_Aberto"));
                    addPropertyIfNotExist(empresaResource, RDFS.label, baseModel.createLiteral(nomeEmpresaPlanilha.trim(), "pt"));

                    if (setorPrincipalPlanilha != null && !setorPrincipalPlanilha.isBlank()) {
                        String nomeSetorNormalizado = normalizarTextoJava(setorPrincipalPlanilha);
                        Resource setorResource = baseModel.createResource(ONT_PREFIX + "Setor_" + nomeSetorNormalizado);
                        addPropertyIfNotExist(setorResource, RDF.type, getResource("Setor_Atuacao"));
                        addPropertyIfNotExist(setorResource, RDFS.label, baseModel.createLiteral(setorPrincipalPlanilha.trim(), "pt"));
                        addPropertyIfNotExist(empresaResource, getProperty("atuaEm"), setorResource);
                    }
                    
                    String[] tickers = tickerPlanilha.split("[,;\\s]+");
                    for (String tickerStr : tickers) {
                        String ticker = tickerStr.trim().toUpperCase();
                        if (ticker.matches("^[A-Z]{4}\\d{1,2}$")) {
                            Resource vmResource = baseModel.createResource(ONT_PREFIX + ticker);
                            addPropertyIfNotExist(vmResource, RDF.type, getResource("Valor_Mobiliario_Negociado"));
                            
                            Resource codigoResource = baseModel.createResource(ONT_PREFIX + "Codigo_" + ticker);
                            addPropertyIfNotExist(codigoResource, RDF.type, getResource("Codigo_Negociacao"));
                            addPropertyIfNotExist(codigoResource, getProperty("ticker"), baseModel.createLiteral(ticker));
                            
                            addPropertyIfNotExist(empresaResource, getProperty("temValorMobiliarioNegociado"), vmResource);
                            addPropertyIfNotExist(vmResource, getProperty("representadoPor"), codigoResource);
                        }
                    }
                    rowsProcessed++;
                } catch (Exception e) {
                    logger.error("Erro ao processar linha {} (Empresa: {}): {}", row.getRowNum() + 1, nomeEmpresaPlanilha, e.getMessage());
                    errors++;
                }
            }
        }
        logger.info("<< Informações de Empresas carregado. {} linhas processadas, {} erros.", rowsProcessed, errors);
    }
    
    private void loadDadosPregaoExcel(String resourcePath) throws IOException {
        logger.info(">> Carregando Dados de Pregão de: {}", resourcePath);
        int rowsProcessed = 0; int errors = 0;

        Resource resourceFile = new ClassPathResource(resourcePath);
        if (!resourceFile.exists()) throw new FileNotFoundException("Arquivo de pregão não encontrado: " + resourcePath);
        
        try (InputStream excelFile = resourceFile.getInputStream(); Workbook workbook = new XSSFWorkbook(excelFile)) {
            Sheet sheet = workbook.getSheetAt(0);
            SimpleDateFormat rdfDateFormat = new SimpleDateFormat("yyyy-MM-dd");

            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue; 

                String ticker = getStringCellValue(row.getCell(4)); // Col E
                if (ticker == null || !ticker.matches("^[A-Z]{4}\\d{1,2}$")) continue;

                try {
                    Date dataPregao = parseDateFromCell(row.getCell(2)); // Col C
                    if (dataPregao == null) { errors++; continue; }
                    String dataFmt = rdfDateFormat.format(dataPregao);
                    
                    Resource valorMobiliarioResource = baseModel.getResource(ONT_PREFIX + ticker);
                    if (!baseModel.containsResource(valorMobiliarioResource)) {
                        logger.warn("Ticker '{}' do pregão não encontrado na base. Pulando linha {}.", ticker, row.getRowNum() + 1);
                        errors++; continue;
                    }
                    
                    String negociadoURI = ONT_PREFIX + "Negociado_" + ticker + "_" + dataFmt.replace("-", "");
                    Resource negociadoResource = baseModel.createResource(negociadoURI, getResource("Negociado_Em_Pregao"));
                    
                    Resource pregaoResource = baseModel.createResource(ONT_PREFIX + "Pregao_" + dataFmt.replace("-", ""), getResource("Pregao"));
                    addPropertyIfNotExist(pregaoResource, getProperty("ocorreEmData"), ResourceFactory.createTypedLiteral(dataFmt, XSDDatatype.XSDdate));

                    addPropertyIfNotExist(valorMobiliarioResource, getProperty("negociado"), negociadoResource);
                    addPropertyIfNotExist(negociadoResource, getProperty("negociadoDurante"), pregaoResource);
                    
                    addNumericPropertyIfValid(negociadoResource, getProperty("precoAbertura"), getNumericCellValue(row.getCell(8))); // Col I
                    addNumericPropertyIfValid(negociadoResource, getProperty("precoFechamento"), getNumericCellValue(row.getCell(12))); // Col M
                    
                    rowsProcessed++;
                } catch (Exception e) {
                    logger.error("Erro ao processar linha {} da planilha de pregão (Ticker: {}): {}", row.getRowNum() + 1, ticker, e.getMessage());
                    errors++;
                }
            }
        }
        logger.info("<< Pregão {} carregado. {} linhas processadas, {} erros.", resourcePath, rowsProcessed, errors);
    }
    
    public List<String> executeQuery(String sparqlQuery, String targetVariable) {
        lock.readLock().lock();
        try {
            if (infModel == null) {
                logger.error("ERRO CRÍTICO: Modelo de inferência não inicializado. A consulta não pode ser executada.");
                return new ArrayList<>();
            }
            logger.info("Executando consulta SPARQL. Variável alvo: '{}'\n---\n{}\n---", targetVariable, sparqlQuery);
            List<String> results = new ArrayList<>();
            Query query;
            try {
                query = QueryFactory.create(sparqlQuery);
            } catch (QueryParseException e) {
                logger.error("ERRO DE SINTAXE na query SPARQL: {}", e.getMessage());
                return null;
            }
            
            try (QueryExecution qexec = QueryExecutionFactory.create(query, infModel)) {
                ResultSet rs = qexec.execSelect();
                while (rs.hasNext()) {
                    QuerySolution soln = rs.nextSolution();
                    RDFNode node = soln.get(targetVariable);
                    if (node != null) {
                        results.add(node.isLiteral() ? node.asLiteral().getLexicalForm() : node.asResource().getURI());
                    }
                }
            } catch (Exception e) {
                logger.error("Erro durante a EXECUÇÃO da query SPARQL.", e);
                return null;
            }
            logger.info("Consulta executada com sucesso. {} resultado(s) encontrado(s).", results.size());
            return results;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    private String normalizarTextoJava(String texto) {
        if (texto == null) return "";
        String nfdNormalizedString = Normalizer.normalize(texto.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        Pattern pattern = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
        return pattern.matcher(nfdNormalizedString).replaceAll("").replaceAll("[^a-z0-9\\s]", "").trim().replaceAll("\\s+", "_");
    }

    private void loadRdfData(String resourcePath, Lang language, String description) throws IOException {
        Resource resourceFile = new ClassPathResource(resourcePath);
        if (!resourceFile.exists()) throw new FileNotFoundException(description + " não encontrado: " + resourcePath);
        try (InputStream in = resourceFile.getInputStream()) {
            RDFDataMgr.read(baseModel, in, language);
            logger.info("✓ {} '{}' carregado com sucesso.", description, resourcePath);
        }
    }

    private void saveInferredModel() {
        try {
            Path outputPath = Paths.get(INFERENCE_OUTPUT_FILE);
            logger.info("Tentando salvar modelo RDF inferido em: {}", outputPath.toAbsolutePath());
            try (OutputStream out = Files.newOutputStream(outputPath)) {
                RDFDataMgr.write(out, infModel, Lang.TURTLE);
                logger.info("✓ Modelo RDF inferido salvo com sucesso.");
            }
        } catch(Exception e) {
            logger.error("! Erro ao salvar modelo inferido: {}", e.getMessage());
        }
    }
    
    private String getStringCellValue(Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.STRING) return cell.getStringCellValue().trim();
        if (cell.getCellType() == CellType.NUMERIC) return String.valueOf((long)cell.getNumericCellValue());
        return cell.toString().trim();
    }

    private double getNumericCellValue(Cell cell) {
        if (cell == null) return Double.NaN;
        if (cell.getCellType() == CellType.NUMERIC) return cell.getNumericCellValue();
        if (cell.getCellType() == CellType.STRING) {
            try { return Double.parseDouble(cell.getStringCellValue().replace(",", ".")); } 
            catch (NumberFormatException e) { return Double.NaN; }
        }
        return Double.NaN;
    }
    
    private Date parseDateFromCell(Cell cell) {
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                if (DateUtil.isCellDateFormatted(cell)) return cell.getDateCellValue();
                return new SimpleDateFormat("yyyyMMdd").parse(String.valueOf((long)cell.getNumericCellValue()));
            }
            if (cell.getCellType() == CellType.STRING) {
                return new SimpleDateFormat("yyyyMMdd").parse(cell.getStringCellValue());
            }
        } catch (Exception e) {
            logger.warn("Não foi possível parsear a data da célula '{}'. Erro: {}", cell, e.getMessage());
            return null;
        }
        return null;
    }

    private Property getProperty(String localName) { return ResourceFactory.createProperty(ONT_PREFIX + localName); }
    private Resource getResource(String localName) { return ResourceFactory.createResource(ONT_PREFIX + localName); }
    private void addPropertyIfNotExist(Resource s, Property p, RDFNode o) { if (s != null && p != null && o != null && !baseModel.contains(s, p, o)) baseModel.add(s, p, o); }
    private void addNumericPropertyIfValid(Resource s, Property p, double val) { if (s != null && p != null && !Double.isNaN(val)) baseModel.add(s, p, ResourceFactory.createTypedLiteral(val)); }
}