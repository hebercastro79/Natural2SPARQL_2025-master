package com.example.Programa_heber.controller;

import com.example.Programa_heber.model.PerguntaRequest;
import com.example.Programa_heber.model.ProcessamentoDetalhadoResposta;
import com.example.Programa_heber.service.QuestionProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*") // Permite requisições de qualquer origem
public class QuestionController {

    @Autowired
    private QuestionProcessor questionProcessor;

    @PostMapping("/question")
    public ResponseEntity<ProcessamentoDetalhadoResposta> processQuestion(@RequestBody PerguntaRequest request) {
        if (request == null || request.getPergunta() == null || request.getPergunta().isBlank()) {
            ProcessamentoDetalhadoResposta erro = new ProcessamentoDetalhadoResposta();
            erro.setErro("A pergunta não pode ser vazia.");
            return ResponseEntity.badRequest().body(erro);
        }

        ProcessamentoDetalhadoResposta resposta = questionProcessor.processQuestion(request.getPergunta());
        return ResponseEntity.ok(resposta);
    }
}