package com.charging.system.repository;

import com.charging.system.entity.Bill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface BillRepository extends JpaRepository<Bill, Long> {
    Optional<Bill> findByBillNumber(String billNumber);

    List<Bill> findByUserId(Long userId);

    // 🟩 新增：动态从分布式数据库里盘查某个物理桩是否正处于充电占用状态
    boolean existsByPileIdAndStatus(String pileId, String status);
}