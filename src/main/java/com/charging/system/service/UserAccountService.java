package com.charging.system.service;

import com.charging.system.entity.UserAccount;
import com.charging.system.repository.AccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.Optional;

@Service
public class UserAccountService {

    @Autowired
    private AccountRepository accountRepository;

    /**
     * 车主/管理员注册账户（支持 ID 角色分段流转，后台锁死 1000 元默认本金）
     */
    public UserAccount register(String username, String password, String role) {
        if (accountRepository.findByUsername(username).isPresent()) {
            throw new RuntimeException("❌ 用户名已存在！");
        }

        UserAccount account = new UserAccount();
        account.setUsername(username);
        account.setPassword(password);

        String upperRole = role.toUpperCase();
        account.setRole(upperRole);

        // 多角色 ID 动态分流
        Long nextId;
        if ("ADMIN".equals(upperRole)) {
            Long maxAdminId = accountRepository.findMaxIdByRole("ADMIN");
            nextId = (maxAdminId == null) ? 9001L : maxAdminId + 1;
        } else {
            Long maxUserId = accountRepository.findMaxIdByRole("USER");
            nextId = (maxUserId == null) ? 1001L : maxUserId + 1;
        }

        account.setId(nextId);
        account.setBalance(BigDecimal.valueOf(1000.00)); // 🟩 严格在后台写死，默认初始本金 1000 元

        return accountRepository.save(account);
    }

    /**
     * 登录校验
     */
    public boolean login(String username, String password) {
        Optional<UserAccount> accountOpt = accountRepository.findByUsername(username);
        if (accountOpt.isPresent()) {
            return accountOpt.get().getPassword().equals(password);
        }
        return false;
    }

    /**
     * 账户充值
     */
    public UserAccount recharge(String username, BigDecimal amount) {
        UserAccount account = accountRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("❌ 账户不存在！"));
        account.setBalance(account.getBalance().add(amount));
        return accountRepository.save(account);
    }
}