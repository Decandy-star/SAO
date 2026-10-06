package com.sao.fakeserver;

import com.sao.fakeserver.config.SaoProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(SaoProperties.class)
public class FakeServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(FakeServerApplication.class, args);
    }
}
