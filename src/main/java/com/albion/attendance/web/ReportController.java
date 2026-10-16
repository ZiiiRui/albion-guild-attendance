package com.albion.attendance.web;

import com.albion.attendance.AttendanceService;
import com.albion.attendance.core.DailyReport;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final AttendanceService service;
    public ReportController(AttendanceService service) { this.service = service; }

    @GetMapping public List<LocalDate> dates(@RequestParam(defaultValue = "31") int limit) {
        return service.dates(limit);
    }
    @GetMapping("/latest") public DailyReport latest() { return service.latestReport(); }
    @GetMapping("/{date}") public DailyReport report(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.report(date);
    }
    @GetMapping("/txt") public ResponseEntity<Resource> text() throws Exception {
        var path = service.textFile();
        if (!Files.isRegularFile(path)) throw new NoSuchElementException("TXT has not been generated");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + path.getFileName() + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(new FileSystemResource(path));
    }
}
