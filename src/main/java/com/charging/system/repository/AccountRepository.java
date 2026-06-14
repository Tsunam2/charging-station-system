package com.charging.system.repository;

import com.charging.system.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface AccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByUsername(String username);

    // 🟩 新增：使用 JPQL 动态查询当前角色在数据库里的最大 ID 编号
    @Query("SELECT MAX(a.id) FROM UserAccount a WHERE a.role = ?1")
    Long findMaxIdByRole(String role);
}