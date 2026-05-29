package com.tap.backend;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan(basePackages = "com.tap.backend.academic.dao")
@ConfigurationPropertiesScan
@EnableJpaRepositories(basePackages = "com.tap.backend")
@EntityScan(basePackages = "com.tap.backend")
@EnableScheduling
public class TeachingAssistantApplication {

	public static void main(String[] args) {
		SpringApplication.run(TeachingAssistantApplication.class, args);
	}

}
