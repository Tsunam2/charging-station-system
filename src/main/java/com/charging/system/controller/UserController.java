package com.charging.system.controller;

import com.charging.system.entity.UserAccount;
import com.charging.system.repository.AccountRepository;
import com.charging.system.service.UserAccountService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/user")
public class UserController {

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private AccountRepository accountRepository;

    /**
     * 🔐 核心登录接口
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestParam String username, @RequestParam String password, HttpSession session) {
        boolean isSuccess = userAccountService.login(username, password);
        if (isSuccess) {
            Optional<UserAccount> accountOpt = accountRepository.findByUsername(username);

            // 🎯 核心修复：将用户信息存入 Session，供后续鉴权使用
            session.setAttribute("user", accountOpt.get());
            session.setAttribute("username", username); // 显式存入用户名，方便跨层级取值

            Map<String, Object> response = new HashMap<>();
            response.put("username", username); // 前端请务必通过此字段进行后续的账单查询请求
            response.put("role", accountOpt.get().getRole());
            response.put("id", accountOpt.get().getId());

            return ResponseEntity.ok(response);
        }
        return ResponseEntity.status(401).body("FAIL:用户名或密码错误");
    }

    /**
     * 🎯 核心新增：获取当前登录用户信息
     * 建议前端在查询账单前，先调用此接口确保获取到准确的 username，避免使用硬编码 ID
     */
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (username == null) {
            return ResponseEntity.status(401).body("未登录");
        }
        Map<String, String> response = new HashMap<>();
        response.put("username", username);
        return ResponseEntity.ok(response);
    }

    /**
     * 车主/管理员注册账户
     */
    @PostMapping("/register")
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
    @PostMapping("/recharge")
    public String recharge(@RequestParam String username, @RequestParam BigDecimal amount) {
        try {
            UserAccount account = userAccountService.recharge(username, amount);
            return "🪙 充值成功！当前最新余额: " + account.getBalance() + "元";
        } catch (Exception e) {
            return e.getMessage();
        }
    }
}