package com.rath.first.project;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class MyFirstAiProjectApplication {

	public static void main(String[] args) {
		SpringApplication.run(MyFirstAiProjectApplication.class, args);
	}

}
