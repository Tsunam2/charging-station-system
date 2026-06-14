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
     * 统一验收四元组事件自适应流转控制网关
     */
    @Transactional
    public String processEvaluationEvent(String eventType, String id, String chargeType, Double value) {
        String logPrefix = "【仿真时间: " + ChargingScheduleEngine.getSimTimeStr() + "】";

        // === 1. A 事件：充电申请进场 ===
        if ("A".equalsIgnoreCase(eventType)) {
            Long userId = Long.parseLong(id.replaceAll("[^0-9]", ""));

            if (ChargingScheduleEngine.WAITING_AREA.size() >= 10) {
                return logPrefix + "❌ 拒绝进场：当前车位等候区已挤满（N=10）！";
            }

            String modeLabel = "FAST".equalsIgnoreCase(chargeType) ? "-FAST-" : "-SLOW-";
            String billNumber = "BILL-" + id + modeLabel + System.currentTimeMillis();

            Bill bill = new Bill();
            bill.setBillNumber(billNumber);
            bill.setUserId(userId);
            bill.setPileId("PENDING");
            bill.setStartTime(LocalDateTime.now()); // 公平时间戳锁定
            bill.setStatus("CHARGING");
            bill.setChargeAmount(BigDecimal.ZERO);
            bill.setExpectedAmount(BigDecimal.valueOf(value));
            bill.setTotalFee(BigDecimal.ZERO);
            bill.setElectricFee(BigDecimal.ZERO);
            bill.setServiceFee(BigDecimal.ZERO);
            billRepository.save(bill);

            ChargingScheduleEngine.WAITING_AREA.add(billNumber);
            scheduleEngine.autoDispatchWaitingArea();

            return logPrefix + "🚗 申请成功！车辆 " + id + " 已排入等候区。";
        }

        // === 2. B 事件：充电桩故障与抢修并网 ===
        if ("B".equalsIgnoreCase(eventType)) {
            String fullPileId = id.startsWith("F") ? "PILE-F" + id.substring(1) : "PILE-T" + id.substring(1);

            if (value == 0) {
                scheduleEngine.handlePileBreakdown(fullPileId);
                return logPrefix + "🚨 报警：物理充电桩 " + fullPileId + " 崩溃！等候区挂起，执行级联灾备调度。";
            } else {
                ChargingScheduleEngine.PILE_HEALTH.put(fullPileId, true);
                scheduleEngine.autoDispatchWaitingArea();
                return logPrefix + "♻️ 恢复：物理充电桩 " + fullPileId + " 恢复健康，重新提供供电能力。";
            }
        }

        // === 3. C 事件：用户动态自适应变更请求 (核心大修) ===
        if ("C".equalsIgnoreCase(eventType)) {
            Long userId = Long.parseLong(id.replaceAll("[^0-9]", ""));
            List<Bill> bills = billRepository.findByUserId(userId);
            Bill activeBill = bills.stream().filter(b -> "CHARGING".equals(b.getStatus())).findFirst().orElse(null);

            if (activeBill == null)
                return logPrefix + "⚠️ 变更忽略：未找到该车主执行中的活动事务。";

            // 🟢 子卡口 A：强行取消充电/拔枪退场
            if (value == 0) {
                String currentPile = activeBill.getPileId();
                boolean wasInPileQueue = false;
                if (ChargingScheduleEngine.PILE_QUEUES.containsKey(currentPile)) {
                    wasInPileQueue = ChargingScheduleEngine.PILE_QUEUES.get(currentPile)
                            .remove(activeBill.getBillNumber());
                }
                ChargingScheduleEngine.WAITING_AREA.remove(activeBill.getBillNumber());

                activeBill.setStatus("UNPAID");
                billRepository.save(activeBill);

                // 物理车位瞬时释放，必须【立刻触发】新一轮叫号
                if (wasInPileQueue) {
                    scheduleEngine.autoDispatchWaitingArea();
                }
                return logPrefix + "🔌 取消：车主 " + id + " 剔除系统，车位物理释放，即时触发等候区级联调度。";
            }

            // 🟢 子卡口 B：跨队列互切充电模式 (快慢充互切)
            boolean currentIsFast = activeBill.getBillNumber().contains("-FAST-");
            boolean targetIsFast = "F".equalsIgnoreCase(chargeType);
            if (!"O".equalsIgnoreCase(chargeType) && (currentIsFast != targetIsFast)) {

                // 从原排队/充电队列中彻底析出
                String currentPile = activeBill.getPileId();
                if (ChargingScheduleEngine.PILE_QUEUES.containsKey(currentPile)) {
                    ChargingScheduleEngine.PILE_QUEUES.get(currentPile).remove(activeBill.getBillNumber());
                }
                ChargingScheduleEngine.WAITING_AREA.remove(activeBill.getBillNumber());

                // 刷新模式标识与单号
                String newModeLabel = targetIsFast ? "-FAST-" : "-SLOW-";
                String oldNo = activeBill.getBillNumber();
                String newNo = oldNo.replace("-FAST-", newModeLabel).replace("-SLOW-", newModeLabel);

                activeBill.setBillNumber(newNo);
                activeBill.setPileId("PENDING");
                activeBill.setExpectedAmount(BigDecimal.valueOf(value));
                billRepository.save(activeBill);

                // ⚖️ 顺位判定公平唯一标尺：依据原始 startTime 时间戳精准寻找插入位置
                int insertPos = ChargingScheduleEngine.WAITING_AREA.size();
                for (int idx = 0; idx < ChargingScheduleEngine.WAITING_AREA.size(); idx++) {
                    String bNo = ChargingScheduleEngine.WAITING_AREA.get(idx);
                    Bill other = billRepository.findByBillNumber(bNo).orElse(null);
                    if (other != null) {
                        boolean otherIsFast = other.getBillNumber().contains("-FAST-");
                        if (otherIsFast == targetIsFast) {
                            if (other.getStartTime().isAfter(activeBill.getStartTime())) {
                                insertPos = idx;
                                break;
                            }
                        }
                    }
                }
                ChargingScheduleEngine.WAITING_AREA.add(insertPos, newNo);
                scheduleEngine.autoDispatchWaitingArea(); // 瞬时触发重调
                return logPrefix + "🔄 模式互切：车主 " + id + " 基于原始时间戳已无缝在目标队列执行公平顺位排队。";
            }

            // 🟢 子卡口 C：单纯修改电量 (不切模式)
            BigDecimal newExpected = BigDecimal.valueOf(value);
            String currentPile = activeBill.getPileId();

            boolean isFirstChargingNode = false;
            if (ChargingScheduleEngine.PILE_QUEUES.containsKey(currentPile)
                    && !ChargingScheduleEngine.PILE_QUEUES.get(currentPile).isEmpty()) {
                if (ChargingScheduleEngine.PILE_QUEUES.get(currentPile).get(0).equals(activeBill.getBillNumber())) {
                    isFirstChargingNode = true;
                }
            }

            if (isFirstChargingNode && newExpected.compareTo(activeBill.getChargeAmount()) <= 0) {
                // 正在充电且减少的目标电量低于已充入电量 ➔ 【即时切断熔断】
                ChargingScheduleEngine.PILE_QUEUES.get(currentPile).remove(activeBill.getBillNumber());
                activeBill.setStatus("UNPAID");
                activeBill.setExpectedAmount(newExpected);
                billRepository.save(activeBill);

                scheduleEngine.autoDispatchWaitingArea(); // 车位瞬间释放触发新调度
                return logPrefix + "🛑 熔断：新修改目标电量低于已充入度数，系统瞬间关闭强电输出并开启清算。";
            } else {
                // 处于常规排队，或增加目标电量延长充电时间 ➔ 顺位不发生改变，仅级联更新
                activeBill.setExpectedAmount(newExpected);
                billRepository.save(activeBill);
                return logPrefix + "⚙️ 自适应：车辆电量参数矩阵已重置，等待时间参数自适应级联刷新完毕。";
            }
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
}