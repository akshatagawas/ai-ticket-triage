package com.project.ai_ticket_triage.controller;

import java.util.List;

import com.project.ai_ticket_triage.dto.SimilarCase;
import com.project.ai_ticket_triage.service.KnowledgeBaseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Debug endpoint: GET /kb/search?q=... shows which past tickets the vector
 * search returns for a piece of text.
 */
@RestController
@RequestMapping("/kb")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBase;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    @GetMapping("/search")
    public List<SimilarCase> search(@RequestParam String q) {
        return knowledgeBase.findSimilar(q, "");
    }
}
