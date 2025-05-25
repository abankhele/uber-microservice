package com.uber.api.payment.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.uber.api")
@EntityScan(basePackages = {"com.uber.api.payment.service", "com.uber.api.shared"})
@EnableJpaRepositories(basePackages = {"com.uber.api.payment.service", "com.uber.api.shared"})
@EnableScheduling
public class PaymentServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(PaymentServiceApplication.class, args);
	}
}
