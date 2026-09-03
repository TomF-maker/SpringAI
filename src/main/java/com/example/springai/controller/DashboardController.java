package com.example.springai.controller;

import com.example.springai.dto.StatisticsDTO;
import com.example.springai.service.DocumentServiceI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    @Autowired
    private DocumentServiceI documentService;

    @GetMapping("/statistics")
    @PreAuthorize("hasRole('ADMIN')")
    public StatisticsDTO getStatistics() {
        return documentService.getStatistics();
    }
}