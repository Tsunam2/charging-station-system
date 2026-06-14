package com.charging.system.service;

import com.charging.system.entity.Bill;
import com.charging.system.entity.UserAccount;
import com.charging.system.repository.AccountRepository;
import com.charging.system.repository.BillRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class ChargingScheduleService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private BillRepository billRepository;

    @Autowired
    private AccountRepository accountRepository;

    private static final String FAST_QUEUE_KEY = "charging:queue:fast";
    private static final String SLOW_QUEUE_KEY = "charging:queue:slow";

    /**
     * 1. 提交订单：严格根据组长给出的时间计费矩阵算法执行前置计算
     */
    public String applyForCharging(Long userId, String chargeType, Integer chargeMinutes) {
        String billNumber = "BILL-" + UUID.randomUUID().toString().replaceAll("-", "").toUpperCase();

        // 贪心算法前置推演桩位
        List<String> targetPiles = "FAST".equalsIgnoreCase(chargeType) ? List.of("PILE-A01", "PILE-A02")
                : List.of("PILE-B01", "PILE-B02");
        String predictedPile = "无可用物理桩";
        String predictedWait = "当前桩全满，预计排队等待约 5-10 分钟";

        for (String pileId : targetPiles) {
            if (!billRepository.existsByPileIdAndStatus(pileId, "CHARGING")) {
                predictedPile = pileId;
                predictedWait = "0秒 (无需排队，后台秒级接入)";
                break;
            }
        }

        // 入队 Redis 缓冲层
        if ("FAST".equalsIgnoreCase(chargeType)) {
            redisTemplate.opsForList().leftPush(FAST_QUEUE_KEY, userId.toString());
        } else {
            redisTemplate.opsForList().leftPush(SLOW_QUEUE_KEY, userId.toString());
        }

        // 🟩 严格执行：慢充30min=5度=6元 | 快充30min=15度=18元
        BigDecimal minutesBD = BigDecimal.valueOf(chargeMinutes);
        BigDecimal chargeAmount;
        BigDecimal totalFee;
        BigDecimal electricFee;
        BigDecimal serviceFee;

        if ("FAST".equalsIgnoreCase(chargeType)) {
            chargeAmount = minutesBD.multiply(BigDecimal.valueOf(15)).divide(BigDecimal.valueOf(30), 2,
                    RoundingMode.HALF_UP);
            totalFee = minutesBD.multiply(BigDecimal.valueOf(18)).divide(BigDecimal.valueOf(30), 2,
                    RoundingMode.HALF_UP);
            electricFee = chargeAmount.multiply(BigDecimal.valueOf(1.2)); // 电费单价1.2元/度
            serviceFee = totalFee.subtract(electricFee);
        } else {
            chargeAmount = minutesBD.multiply(BigDecimal.valueOf(5)).divide(BigDecimal.valueOf(30), 2,
                    RoundingMode.HALF_UP);
            totalFee = minutesBD.multiply(BigDecimal.valueOf(6)).divide(BigDecimal.valueOf(30), 2,
                    RoundingMode.HALF_UP);
            electricFee = chargeAmount.multiply(BigDecimal.valueOf(1.2));
            serviceFee = totalFee.subtract(electricFee);
        }

        // 核心详单草稿落盘
        Bill bill = new Bill();
        bill.setBillNumber(billNumber);
        bill.setUserId(userId);
        bill.setPileId("PENDING");
        bill.setStartTime(LocalDateTime.now());
        bill.setChargeAmount(chargeAmount);
        bill.setElectricFee(electricFee);
        bill.setServiceFee(serviceFee);
        bill.setTotalFee(totalFee);
        bill.setStatus("CHARGING");
        billRepository.save(bill);

        // 返回前置预测核心数据块给前端监控屏
        return billNumber + ":" + predictedPile + ":" + predictedWait + ":" + totalFee + ":" + chargeMinutes;
    }

    public void stopCharging(String billNumber) {
        Bill bill = billRepository.findByBillNumber(billNumber)
                .orElseThrow(() -> new RuntimeException("❌ 订单不存在"));

        if (!"CHARGING".equals(bill.getStatus()))
            return;

        if ("PENDING".equals(bill.getPileId())) {
            bill.setTotalFee(BigDecimal.ZERO);
            bill.setElectricFee(BigDecimal.ZERO);
            bill.setServiceFee(BigDecimal.ZERO);
            bill.setStatus("PAID");
            bill.setEndTime(LocalDateTime.now());
            billRepository.save(bill);

            redisTemplate.opsForList().remove(FAST_QUEUE_KEY, 1, bill.getUserId().toString());
            redisTemplate.opsForList().remove(SLOW_QUEUE_KEY, 1, bill.getUserId().toString());
            return;
        }

        String occupiedPileId = bill.getPileId();
        ChargingScheduleEngine.releasePile(occupiedPileId);

        bill.setEndTime(LocalDateTime.now());
        bill.setStatus("UNPAID");
        billRepository.save(bill);
    }

    /**
     * 🟩 微信支付安全核销：扣除本金并向前端回传实付总金额
     */
    @Transactional
    public String payBill(String billNumber) {
        Bill bill = billRepository.findByBillNumber(billNumber).orElseThrow(() -> new RuntimeException("❌ 订单不存在"));

        if (bill.getTotalFee().compareTo(BigDecimal.ZERO) == 0) {
            return "SUCCESS:0.00";
        }

        if (!"UNPAID".equals(bill.getStatus()))
            return "⚠️ 无需重复支付";

        UserAccount account = accountRepository.findById(bill.getUserId())
                .orElseThrow(() -> new RuntimeException("❌ 未找到车主账户"));
        if (account.getBalance().compareTo(bill.getTotalFee()) < 0) {
            throw new RuntimeException("❌ 微信支付失败：余额不足！");
        }

        account.setBalance(account.getBalance().subtract(bill.getTotalFee()));
        accountRepository.save(account);

        bill.setStatus("PAID");
        billRepository.save(bill);

        // 🟩 吐出实扣总金额
        return "SUCCESS:" + bill.getTotalFee();
    }

    public Bill getBillStatus(String billNumber) {
        return billRepository.findByBillNumber(billNumber).orElse(null);
    }
}