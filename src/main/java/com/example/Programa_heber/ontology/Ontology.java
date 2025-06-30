package com.example.Programa_heber.ontology;

import jakarta.annotation.PostConstruct;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.reasoner.Reasoner;
import org.apache.jena.reasoner.ReasonerRegistry;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

@Component
public class Ontology {
    private static final Logger logger = LoggerFactory.getLogger(Ontology.class);
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private static final String ONT_PREFIX = "https://dcm.ffclrp.usp.br/lssb/stock-market-ontology#";
    private Model baseModel;
    private InfModel infModel;

    @PostConstruct
    public void init() {
        logger.info(">>> INICIANDO Inicialização da Ontologia...");
        lock.writeLock().lock();
        try {
            baseModel = ModelFactory.createDefaultModel();
            baseModel.setNsPrefix("b3", ONT_PREFIX);
            baseModel.setNsPrefix("rdfs", RDFS.getURI());
            baseModel.setNsPrefix("xsd", XSDDatatype.XSD + "#");

            loadRdfData("ontologiaB3.ttl", Lang.TURTLE, "Esquema base da Ontologia");
            loadInformacoesEmpresas("Templates/Informacoes_Empresas.xlsx");
            loadDadosPregaoExcel("Datasets/dados_novos_anterior.xlsx");
            loadDadosPregaoExcel("Datasets/dados_novos_atual.xlsx");

            logger.info("Total triplas base: {}. Criando modelo de inferência...", baseModel.size());
            Reasoner reasoner = ReasonerRegistry.getRDFSReasoner();
            infModel = ModelFactory.createInfModel(reasoner, baseModel);
            logger.info("Modelo de inferência criado. Total triplas: {}", infModel.size());
            saveInferredModel();
            logger.info("<<< ONTOLOGIA INICIALIZADA COM SUCESSO >>>");
        } catch (Exception e) {
            logger.error("!!!!!!!! FALHA GRAVE NA INICIALIZAÇÃO DA ONTOLOGY !!!!!!!!", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void loadInformacoesEmpresas(String resourcePath) throws IOException {
        logger.info(">> Carregando Informações de Empresas de: {}", resourcePath);
        int rowsProcessed = 0;
        var resourceFile = new ClassPathResource(resourcePath);
        try (InputStream excelFile = resourceFile.getInputStream(); Workbook workbook = new XSSFWorkbook(excelFile)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue;
                String nomeEmpresa = getStringCellValue(row.getCell(0)); // Col A
                String tickersStr = getStringCellValue(row.getCell(1));  // Col B
                String setor = getStringCellValue(row.getCell(5));        // Col F
                if (nomeEmpresa == null || tickersStr == null) continue;
                
                Resource empresaResource = baseModel.createResource(ONT_PREFIX + "Empresa_" + normalizarTextoJava(nomeEmpresa));
                add(empresaResource, RDFS.label, baseModel.createLiteral(nomeEmpresa.trim(), "pt"));
                add(empresaResource, RDF.type, getResource("Empresa_Capital_Aberto"));

                if (setor != null) {
                    Resource setorResource = baseModel.createResource(ONT_PREFIX + "Setor_" + normalizarTextoJava(setor));
                    add(setorResource, RDFS.label, baseModel.createLiteral(setor.trim(), "pt"));
                    add(empresaResource, getProperty("atuaEm"), setorResource);
                }

                for (String ticker : tickersStr.split("[,;\\s]+")) {
                    ticker = ticker.trim().toUpperCase();
                    if (ticker.matches("^[A-Z]{4}\\d{1,2}$")) {
                        Resource vmResource = baseModel.createResource(ONT_PREFIX + ticker);
                        Resource codigoResource = baseModel.createResource(ONT_PREFIX + "Codigo_" + ticker);
                        add(vmResource, RDF.type, getResource("Valor_Mobiliario_Negociado"));
                        add(codigoResource, getProperty("ticker"), baseModel.createLiteral(ticker));
                        add(empresaResource, getProperty("temValorMobiliarioNegociado"), vmResource);
                        add(vmResource, getProperty("representadoPor"), codigoResource);
                    }
                }
                rowsProcessed++;
            }
        }
        logger.info("<< Informações de Empresas carregado. {} linhas processadas.", rowsProcessed);
    }

    private void loadDadosPregaoExcel(String resourcePath) throws IOException {
        logger.info(">> Carregando Dados de Pregão de: {}", resourcePath);
        int rowsProcessed = 0;
        var resourceFile = new ClassPathResource(resourcePath);
        try (InputStream excelFile = resourceFile.getInputStream(); Workbook workbook = new XSSFWorkbook(excelFile)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue;
                String ticker = getStringCellValue(row.getCell(4)); // Col E
                if (ticker == null || !ticker.matches("^[A-Z]{4}\\d{1,2}$")) continue;
                
                Date dataPregao = parseDateFromCell(row.getCell(2)); // Col C
                if (dataPregao == null) continue;
                String dataFmt = new SimpleDateFormat("yyyy-MM-dd").format(dataPregao);

                Resource vmResource = baseModel.getResource(ONT_PREFIX + ticker);
                if (!baseModel.containsResource(vmResource)) continue;
                
                Resource negociadoResource = baseModel.createResource(ONT_PREFIX + "Negociado_" + ticker + "_" + dataFmt.replace("-", ""));
                Resource pregaoResource = baseModel.createResource(ONT_PREFIX + "Pregao_" + dataFmt.replace("-", ""));
                
                add(vmResource, getProperty("negociado"), negociadoResource);
                add(negociadoResource, RDF.type, getResource("Negociado_Em_Pregao"));
                add(negociadoResource, getProperty("negociadoDurante"), pregaoResource);
                add(pregaoResource, getProperty("ocorreEmData"), ResourceFactory.createTypedLiteral(dataFmt, XSDDatatype.XSDdate));
                
                addNumericPropertyIfValid(negociadoResource, getProperty("precoAbertura"), getNumericCellValue(row.getCell(8)));
                addNumericPropertyIfValid(negociadoResource, getProperty("precoFechamento"), getNumericCellValue(row.getCell(12)));
                rowsProcessed++;
            }
        }
        logger.info("<< Pregão {} carregado. {} linhas processadas.", resourcePath, rowsProcessed);
    }

    public List<String> executeQuery(String sparqlQuery, String targetVariable) {
        lock.readLock().lock();
        try {
            if (infModel == null) return Collections.emptyList();
            List<String> results = new ArrayList<>();
            Query query = QueryFactory.create(sparqlQuery);
            try (QueryExecution qexec = QueryExecutionFactory.create(query, infModel)) {
                ResultSet rs = qexec.execSelect();
                while (rs.hasNext()) {
                    QuerySolution soln = rs.nextSolution();
                    RDFNode node = soln.get(targetVariable);
                    if (node != null) {
                        results.add(node.isLiteral() ? node.asLiteral().getLexicalForm() : node.asResource().getURI());
                    }
                }
            }
            logger.info("Query executada, {} resultados para '{}'.", results.size(), targetVariable);
            return results;
        } catch (Exception e) {
            logger.error("Erro na execução da query SPARQL: {}", e.getMessage());
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    // --- Métodos Auxiliares ---
    private String normalizarTextoJava(String texto) {
        if (texto == null) return "";
        String nfd = Normalizer.normalize(texto.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return Pattern.compile("\\p{InCombiningDiacriticalMarks}+").matcher(nfd)
                .replaceAll("").replaceAll("[^a-z0-9\\s]", "").trim().replaceAll("\\s+", "_");
    }

    private void loadRdfData(String path, Lang lang, String desc) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            RDFDataMgr.read(baseModel, in, lang);
            logger.info("✓ {} '{}' carregado.", desc, path);
        }
    }

    private void saveInferredModel() {
        try (OutputStream out = new FileOutputStream(INFERENCE_OUTPUT_FILE)) {
            RDFDataMgr.write(out, infModel, Lang.TURTLE);
            logger.info("✓ Modelo RDF inferido salvo em {}", new File(INFERENCE_OUTPUT_FILE).getAbsolutePath());
        } catch (Exception e) {
            logger.warn("! Não foi possível salvar modelo inferido: {}", e.getMessage());
        }
    }

    private String getStringCellValue(Cell cell) {
        if (cell == null) return null;
        return new DataFormatter().formatCellValue(cell).trim();
    }
    private double getNumericCellValue(Cell cell) {
        if (cell == null || cell.getCellType() != CellType.NUMERIC) return Double.NaN;
        return cell.getNumericCellValue();
    }
    private Date parseDateFromCell(Cell cell) {
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC) return cell.getDateCellValue();
            if (cell.getCellType() == CellType.STRING) return new SimpleDateFormat("yyyyMMdd").parse(cell.getStringCellValue());
        } catch (Exception ignored) {}
        return null;
    }
    private Property getProperty(String name) { return baseModel.createProperty(ONT_PREFIX + name); }
    private Resource getResource(String name) { return baseModel.createResource(ONT_PREFIX + name); }
    private void add(Resource s, Property p, RDFNode o) { if (s != null && p != null && o != null && !baseModel.contains(s, p, o)) baseModel.add(s, p, o); }
    private void addNumericPropertyIfValid(Resource s, Property p, double val) { if (!Double.isNaN(val)) add(s, p, ResourceFactory.createTypedLiteral(val)); }
}