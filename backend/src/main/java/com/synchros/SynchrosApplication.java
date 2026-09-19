package com.Synchros;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SynchrosApplication {

    public static void main(String[] args) {
        SpringApplication.run(SynchrosApplication.class, args);
    }
}
