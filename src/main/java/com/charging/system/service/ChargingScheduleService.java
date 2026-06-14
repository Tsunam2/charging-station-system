package com.charging.system.service;

import com.charging.system.entity.Bill;
import com.charging.system.entity.UserAccount;
import com.charging.system.repository.AccountRepository;
import com.charging.system.repository.BillRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class ChargingScheduleService {

    @Autowired
    private BillRepository billRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ChargingScheduleEngine scheduleEngine;

    /**
     * 🟩 统一验收四元组事件总线网关
     * 
     * @param eventType  A(申请), B(故障), C(变更)
     * @param id         车辆ID(V1) 或 桩ID(F1/T1)
     * @param chargeType F(快充), T(慢充), O(不变更)
     * @param value      电量度数 或 故障状态(0/1)
     */
    @Transactional
    public String processEvaluationEvent(String eventType, String id, String chargeType, Double value) {
        String logPrefix = "【仿真时间: " + ChargingScheduleEngine.getSimTimeStr() + "】";

        // === 1. A 事件：充电申请进场 ===
        if ("A".equalsIgnoreCase(eventType)) {
            Long userId = Long.parseLong(id.replaceAll("[^0-9]", ""));

            // 校验等候区是否突破最大容量上限 N=10
            if (ChargingScheduleEngine.WAITING_AREA.size() >= 10) {
                return logPrefix + "❌ 拒绝进场：当前车位等候区已挤满（N=10）！车主 " + id + " 只能驶离。";
            }

            String modeLabel = "FAST".equalsIgnoreCase(chargeType) ? "-FAST-" : "-SLOW-";
            String billNumber = "BILL-" + id + modeLabel + System.currentTimeMillis();

            Bill bill = new Bill();
            bill.setBillNumber(billNumber);
            bill.setUserId(userId);
            bill.setPileId("PENDING");
            bill.setStartTime(LocalDateTime.now());
            bill.setStatus("CHARGING");
            bill.setChargeAmount(BigDecimal.ZERO);
            billRepository.save(bill);

            // 塞入等候区尾部进行次序排列
            ChargingScheduleEngine.WAITING_AREA.add(billNumber);
            // 瞬时触发一次排队调度探测
            scheduleEngine.autoDispatchWaitingArea();

            return logPrefix + "🚗 申请成功！车辆 " + id + " 已排入等候区。预计所需充电资产核算中...";
        }

        // === 2. B 事件：硬件故障中断与恢复 ===
        if ("B".equalsIgnoreCase(eventType)) {
            String fullPileId = id.startsWith("F") ? "PILE-F" + id.substring(1) : "PILE-T" + id.substring(1);

            if (value == 0) {
                // 触发现场验收必考的故障转移算法
                scheduleEngine.handlePileBreakdown(fullPileId);
                return logPrefix + "🚨 报警：物理充电桩 " + fullPileId + " 突发故障崩溃！已紧急锁死调度总线。";
            } else {
                // 故障恢复
                ChargingScheduleEngine.PILE_HEALTH.put(fullPileId, true);
                scheduleEngine.autoDispatchWaitingArea(); // 恢复等候区派发
                return logPrefix + "♻️ 恢复：物理充电桩 " + fullPileId + " 抢修成功，重新并网提供供电能力！";
            }
        }

        // === 3. C 事件：中途变更请求 ===
        if ("C".equalsIgnoreCase(eventType)) {
            Long userId = Long.parseLong(id.replaceAll("[^0-9]", ""));
            List<Bill> bills = billRepository.findByUserId(userId);
            Bill activeBill = bills.stream().filter(b -> "CHARGING".equals(b.getStatus())).findFirst().orElse(null);

            if (activeBill == null)
                return logPrefix + "⚠️ 未找到该用户正在执行的充电订单";

            // 如果数值为0，代表车主强行中止充电取消退场
            if (value == 0) {
                String currentPile = activeBill.getPileId();
                if (ChargingScheduleEngine.PILE_QUEUES.containsKey(currentPile)) {
                    ChargingScheduleEngine.PILE_QUEUES.get(currentPile).remove(activeBill.getBillNumber());
                }
                ChargingScheduleEngine.WAITING_AREA.remove(activeBill.getBillNumber());

                activeBill.setStatus("UNPAID");
                billRepository.save(activeBill);
                return logPrefix + "🔌 变更捕获：车主 " + id + " 申请强行中止充电，正在执行微信清算...";
            }

            return logPrefix + "🔄 变更捕获：车主变更了其充电模型的参数矩阵。";
        }

        return "UNKNOWN_EVENT";
    }

    @Transactional
    public String payBill(String billNumber) {
        Bill bill = billRepository.findByBillNumber(billNumber).orElseThrow(() -> new RuntimeException("❌ 订单不存在"));
        if (!"UNPAID".equals(bill.getStatus()))
            return "⚠️ 无需重复扣款";

        UserAccount account = accountRepository.findById(bill.getUserId())
                .orElseThrow(() -> new RuntimeException("❌ 未找到账户"));
        if (account.getBalance().compareTo(bill.getTotalFee()) < 0) {
            throw new RuntimeException("❌ 微信清算失败：钱包余额不足！");
        }

        account.setBalance(account.getBalance().subtract(bill.getTotalFee()));
        accountRepository.save(account);

        bill.setStatus("PAID");
        billRepository.save(bill);
        return "SUCCESS:" + bill.getTotalFee();
    }

    public Bill getBillStatus(String billNumber) {
        return billRepository.findByBillNumber(billNumber).orElse(null);
    }
}