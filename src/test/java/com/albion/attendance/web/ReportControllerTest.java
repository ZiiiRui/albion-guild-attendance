package com.albion.attendance.web;

import com.albion.attendance.AttendanceService;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReportControllerTest {
    private AttendanceService service;
    private MockMvc mvc;
    @BeforeEach void setup() {
        service = mock(AttendanceService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ReportController(service)).setControllerAdvice(new ApiErrors()).build();
    }
    @Test void invalidDateIsBadRequest() throws Exception {
        mvc.perform(get("/api/reports/not-a-date")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void writeRequestsAreNotAllowed() throws Exception {
        mvc.perform(post("/api/reports/latest")).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(service);
    }
    @Test void missingReportIsNotFound() throws Exception {
        when(service.latestReport()).thenThrow(new NoSuchElementException("No collected reports"));
        mvc.perform(get("/api/reports/latest")).andExpect(status().isNotFound());
    }
    @Test void listUsesDefaultLimit() throws Exception {
        when(service.dates(31)).thenReturn(List.of(LocalDate.of(2026, 10, 15)));
        mvc.perform(get("/api/reports")).andExpect(status().isOk());
        verify(service).dates(31);
    }
    @Test void backendFailureDoesNotLeakDetails() throws Exception {
        when(service.latestReport()).thenThrow(new RuntimeException("secret database connection details"));
        mvc.perform(get("/api/reports/latest")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Report data is temporarily unavailable"));
    }
}
