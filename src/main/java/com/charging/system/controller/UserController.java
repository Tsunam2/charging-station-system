package com.charging.system.controller;

import com.charging.system.entity.UserAccount;
import com.charging.system.repository.AccountRepository;
import com.charging.system.service.UserAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.math.BigDecimal;
import java.util.Optional;

@RestController
@RequestMapping("/api/user")
public class UserController {

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private AccountRepository accountRepository;

    /**
     * 🔐 核心登录接口（支持多角色判定）
     */
    @GetMapping("/login")
    public String login(@RequestParam String username, @RequestParam String password) {
        boolean isSuccess = userAccountService.login(username, password);
        if (isSuccess) {
            Optional<UserAccount> accountOpt = accountRepository.findByUsername(username);
            return "SUCCESS:" + accountOpt.get().getRole() + ":" + accountOpt.get().getId();
        }
        return "FAIL:用户名或密码错误";
    }

    /**
     * 车主/管理员注册账户（初始赠送 1000 元）
     */
    @GetMapping("/register")
    public String register(@RequestParam String username, @RequestParam String password, @RequestParam String role) {
        try {
            UserAccount account = userAccountService.register(username, password, role);
            return "🟩 注册成功！用户ID: " + account.getId() + "，角色: " + account.getRole() + "，初始余额: " + account.getBalance()
                    + "元";
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /**
     * 账户充值
     */
    @GetMapping("/recharge")
    public String recharge(@RequestParam String username, @RequestParam BigDecimal amount) {
        try {
            UserAccount account = userAccountService.recharge(username, amount);
            return "🪙 充值成功！当前最新余额: " + account.getBalance() + "元";
        } catch (Exception e) {
            return e.getMessage();
        }
    }
}