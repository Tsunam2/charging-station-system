package com.charging.system.config;

import com.charging.system.entity.UserAccount;
import com.charging.system.repository.AccountRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

@Configuration
public class DataInitializer {

    @Bean
    public CommandLineRunner initData(AccountRepository accountRepository) {
        return args -> {
            if (accountRepository.findByUsername("admin").isEmpty()) {
                UserAccount admin = new UserAccount();
                admin.setUsername("admin");
                admin.setPassword("admin");
                admin.setRole("ADMIN");
                accountRepository.save(admin);
            }
            // 自动预埋 V1 到 V30 测试沙盒账号
            for (int i = 1; i <= 30; i++) {
                String uName = "V" + i;
                if (accountRepository.findByUsername(uName).isEmpty()) {
                    UserAccount u = new UserAccount();
                    u.setUsername(uName);
                    u.setPassword("123456");
                    u.setRole("USER");
                    u.setBalance(new BigDecimal("1000.00"));
                    accountRepository.save(u);
                }
            }
        };
    }
}