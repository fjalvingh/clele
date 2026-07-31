package com.clele.parts.controller;

import com.clele.parts.dto.PrintJobDTO;
import com.clele.parts.dto.PrintJobRequest;
import com.clele.parts.service.PrintJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Browser-facing: enqueue a print job for one of the current user's daemons, and poll its status. */
@RestController
@RequestMapping("/api/print-jobs")
@RequiredArgsConstructor
@Tag(name = "Print jobs", description = "Enqueue and track label-print jobs sent to a daemon")
public class PrintJobController {

    private final PrintJobService printJobService;

    @PostMapping
    @Operation(summary = "Enqueue a label print job for a daemon")
    public PrintJobDTO create(@RequestBody PrintJobRequest request) {
        return printJobService.enqueue(request);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Poll a print job's status")
    public PrintJobDTO getStatus(@PathVariable Long id) {
        return printJobService.getStatus(id);
    }
}
