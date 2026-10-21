package br.edu.unievangelica.iot.borda;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BordaApplication {
    public static void main(String[] args) throws IOException {
        Files.createDirectories(Path.of("data")); // pasta do arquivo SQLite
        SpringApplication.run(BordaApplication.class, args);
    }
}
