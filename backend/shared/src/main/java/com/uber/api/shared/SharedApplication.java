package com.uber.api.shared;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SharedApplication {

	public static void main(String[] args) {
		SpringApplication.run(SharedApplication.class, args);
	}

}
