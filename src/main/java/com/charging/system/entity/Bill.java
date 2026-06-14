package com.charging.system.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_bill")
public class Bill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 64)
    private String billNumber; // 全局唯一账单流水号

    @Column(nullable = false)
    private Long userId; // 关联的车主用户ID

    @Column(nullable = false)
    private String pileId; // 关联的充电桩物理编号（如：A01, B02）

    @Column(nullable = false)
    private LocalDateTime startTime; // 充电开始时间

    private LocalDateTime endTime; // 充电结束时间

    @Column(precision = 10, scale = 2)
    private BigDecimal chargeAmount = BigDecimal.ZERO; // 充电电量 (度)

    @Column(precision = 10, scale = 2)
    private BigDecimal electricFee = BigDecimal.ZERO; // 电费金额

    @Column(precision = 10, scale = 2)
    private BigDecimal serviceFee = BigDecimal.ZERO; // 服务费金额

    @Column(precision = 10, scale = 2)
    private BigDecimal totalFee = BigDecimal.ZERO; // 总费用 (电费 + 服务费)

    @Column(nullable = false, length = 20)
    private String status; // "CHARGING" 充电中, "UNPAID" 待支付, "PAID" 已完结
}