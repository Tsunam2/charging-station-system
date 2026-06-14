package com.charging.system;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling; // 引入定时任务特性

@EnableScheduling // 🟩 开启定时调度引擎开关
@SpringBootApplication
public class SystemApplication {
	public static void main(String[] args) {
		SpringApplication.run(SystemApplication.class, args);
	}
}