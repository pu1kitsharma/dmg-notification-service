package com.dmg.notify.tenant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/limits/global")
public class GlobalLimitController {
    public record LimitDto(@Min(1) @Max(1_000_000) int ratePerSecond, @Min(1) @Max(10_000_000) int burst) {}

    private final GlobalLimitRepository repo;

    public GlobalLimitController(GlobalLimitRepository repo) { this.repo = repo; }

    @GetMapping
    public LimitDto get() {
        GlobalLimit g = repo.findById(GlobalLimit.ID).orElseThrow();
        return new LimitDto(g.getRatePerSecond(), g.getBurst());
    }

    @PutMapping
    @Transactional
    public LimitDto put(@Valid @RequestBody LimitDto dto) {
        GlobalLimit g = repo.findById(GlobalLimit.ID).orElseThrow();
        g.set(dto.ratePerSecond(), dto.burst());
        return dto;
    }
}
