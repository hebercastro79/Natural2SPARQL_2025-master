package com.example.Programa_heber;

import com.example.Programa_heber.model.PerguntaRequest;
import com.example.Programa_heber.model.ProcessamentoDetalhadoResposta;
import com.example.Programa_heber.service.QuestionProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Classe principal da aplicação Spring Boot.
 * Esta classe serve como o ponto de entrada para a aplicação e também como o
 * controlador REST que expõe os endpoints da API.
 */
@SpringBootApplication
@RestController
@CrossOrigin(origins = "*") // Permite requisições de qualquer frontend. Para produção, pode ser mais restritivo.
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    // Injeção do nosso serviço principal que contém a lógica de negócio.
    private final QuestionProcessor questionProcessor;

    /**
     * Construtor recomendado para injeção de dependências.
     * O Spring Boot automaticamente fornecerá uma instância de QuestionProcessor.
     * @param questionProcessor O serviço que processa as perguntas.
     */
    @Autowired
    public Main(QuestionProcessor questionProcessor) {
        this.questionProcessor = questionProcessor;
    }

    /**
     * Ponto de entrada principal que inicia a aplicação Spring Boot.
     * @param args Argumentos de linha de comando.
     */
    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
        logger.info(">>> Aplicação Natural2SPARQL iniciada e pronta para receber requisições na porta 8080. <<<");
    }

    /**
     * Endpoint para processar uma pergunta enviada em linguagem natural.
     * Espera um POST para /processar_pergunta com um corpo JSON como: {"pergunta": "Qual o preço da PETR4?"}
     *
     * @param request O objeto da requisição contendo a pergunta.
     * @return Um ResponseEntity contendo o objeto ProcessamentoDetalhadoResposta com a query gerada e o resultado.
     */
    @PostMapping("/processar_pergunta")
    public ResponseEntity<ProcessamentoDetalhadoResposta> processarPergunta(@RequestBody PerguntaRequest request) {
        // Validação da entrada
        if (request == null || request.getPergunta() == null || request.getPergunta().trim().isEmpty()) {
            logger.warn("Recebida requisição para /processar_pergunta sem uma pergunta válida.");
            ProcessamentoDetalhadoResposta errorReply = new ProcessamentoDetalhadoResposta();
            errorReply.setErro("A pergunta não pode ser vazia.");
            return ResponseEntity.badRequest().body(errorReply);
        }

        logger.info("Recebida pergunta para processamento: '{}'", request.getPergunta());

        // Delega o trabalho pesado para a camada de serviço
        ProcessamentoDetalhadoResposta respostaDetalhada = questionProcessor.processQuestion(request.getPergunta());

        // Se o serviço retornou uma mensagem de erro, consideramos uma falha interna
        if (respostaDetalhada.getErro() != null) {
            logger.error("Erro retornado pelo serviço QuestionProcessor: {}", respostaDetalhada.getErro());
            // Retorna um status HTTP 500 para indicar um problema no servidor
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respostaDetalhada);
        }

        logger.info("Pergunta processada com sucesso. Resposta: {}", respostaDetalhada.getResposta());
        // Se tudo correu bem, retorna um status 200 OK com a resposta.
        return ResponseEntity.ok(respostaDetalhada);
    }
}