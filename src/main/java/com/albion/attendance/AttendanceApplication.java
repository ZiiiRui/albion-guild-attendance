package com.albion.attendance;

import com.albion.attendance.core.AttendanceCollector;
import com.albion.attendance.storage.AttendanceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AttendanceProperties.class)
public class AttendanceApplication {
    public static void main(String[] args) {
        String command = Arrays.stream(args).filter(a -> a.startsWith("--attendance.command="))
                .map(a -> a.substring("--attendance.command=".length())).findFirst().orElse("serve");
        if (!java.util.Set.of("serve", "fetch", "export").contains(command)) {
            throw new IllegalArgumentException("attendance.command must be serve, fetch, or export");
        }
        SpringApplication application = new SpringApplication(AttendanceApplication.class);
        if (!command.equals("serve")) application.setWebApplicationType(WebApplicationType.NONE);
        var context = application.run(args);
        if (!command.equals("serve")) context.close();
    }

    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean AttendanceCollector collector() { return new AttendanceCollector(); }
    @Bean(initMethod = "initialize")
    AttendanceRepository repository(DataSource dataSource, ObjectMapper mapper) {
        return new AttendanceRepository(dataSource, mapper);
    }
    @Bean ApplicationRunner commandRunner(AttendanceService service) {
        return args -> {
            String command = args.containsOption("attendance.command")
                    ? args.getOptionValues("attendance.command").getFirst() : "serve";
            if (command.equals("fetch")) {
                LocalDate day = args.containsOption("attendance.date")
                        ? LocalDate.parse(args.getOptionValues("attendance.date").getFirst()) : null;
                service.fetch(day);
            } else if (command.equals("export")) service.export();
        };
    }
}
