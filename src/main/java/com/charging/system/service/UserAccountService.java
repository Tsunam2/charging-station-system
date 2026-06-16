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

    // ==============================================================================
    // 🎯 软工大作业适配器专区 (Adapter) - 用于完美契合大作业 UML 注册与密码设置要求
    // ==============================================================================

    /**
     * UML映射：1、创建新账号
     * 将文档要求的 car_Id, userName, car_Capacity 进行拆解并映射到底层实际业务
     */
    public String createNewAccount(String car_Id, String userName, Double car_Capacity) {
        try {
            // 在我们的高内聚系统中，车牌号/用户名是唯一标识。
            // 为了模拟文档的两步注册逻辑（先创建，后设密码），此处分配一个初始占位密码
            // car_Capacity 参数在实际调度中由车辆提交的 Request_Amount 替代，此处作透传忽略
            register(car_Id, "PENDING_PWD_SETUP", "USER");
            return "Return(1)";
        } catch (Exception e) {
            return "Return(0)";
        }
    }

    /**
     * UML映射：2、验证数据/设置密码
     * 将第一步初始化的账号更新为用户实际提供的密码
     */
    public String set_pwd(String car_Id, String password) {
        Optional<UserAccount> accountOpt = accountRepository.findByUsername(car_Id);
        if (accountOpt.isPresent()) {
            UserAccount account = accountOpt.get();
            account.setPassword(password);
            accountRepository.save(account);
            return "Return(1)";
        }
        return "Return(0)";
    }
}