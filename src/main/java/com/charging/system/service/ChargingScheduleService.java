package com.charging.system.service;

import com.charging.system.entity.Bill;
import com.charging.system.entity.UserAccount;
import com.charging.system.repository.AccountRepository;
import com.charging.system.repository.BillRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    @Transactional
    public String processEvaluationEvent(String eventType, String id, String chargeType, Double value) {
        String logPrefix = "【仿真时间: " + ChargingScheduleEngine.getSimTimeStr() + "】";

        if ("A".equalsIgnoreCase(eventType)) {
            // 🎯 核心修复 1：彻底抛弃正则提取数字的粗暴做法，改用真实 username 精确关联数据库物理 ID！
            UserAccount account = accountRepository.findByUsername(id).orElse(null);
            if (account == null) {
                return logPrefix + "❌ 非法请求：系统未找到注册车主 " + id;
            }
            Long userId = account.getId();

            if (ChargingScheduleEngine.WAITING_AREA.size() >= 10) {
                return logPrefix + "❌ 拒绝进场：当前车位等候区已挤满（N=10）！";
            }

            boolean isFastMode = "FAST".equalsIgnoreCase(chargeType) || "F".equalsIgnoreCase(chargeType);
            String modeLabel = isFastMode ? "-FAST-" : "-SLOW-";
            String billNumber = "BILL-" + id + modeLabel + System.currentTimeMillis();

            Bill bill = new Bill();
            bill.setBillNumber(billNumber);
            bill.setUserId(userId);

            // 🎯 核心新增：将前端传来的真实车牌号（如 "V1"）强绑定到账单实体中，彻底脱离底层自增 ID 偏移的苦海！
            bill.setUsername(id);

            bill.setPileId("PENDING");

            LocalDateTime virtualStamp = LocalDateTime.of(LocalDate.now(), ChargingScheduleEngine.getSimTime());
            bill.setStartTime(virtualStamp);

            bill.setStatus("CHARGING");
            bill.setChargeAmount(BigDecimal.ZERO);
            bill.setExpectedAmount(BigDecimal.valueOf(value));
            bill.setTotalFee(BigDecimal.ZERO);
            bill.setElectricFee(BigDecimal.ZERO);
            bill.setServiceFee(BigDecimal.ZERO);
            bill.setValleyPower(BigDecimal.ZERO);
            bill.setFlatPower(BigDecimal.ZERO);
            bill.setPeakPower(BigDecimal.ZERO);

            // 🎯 核心修复 2：为新接入车辆同步初始化 10 分钟脉冲统计器
            bill.setTotalChargingMinutes(0);

            billRepository.save(bill);

            ChargingScheduleEngine.WAITING_AREA.add(billNumber);
            scheduleEngine.autoDispatchWaitingArea();
            return logPrefix + "🚗 申请成功！车辆 " + id + " 已排入等候区。";
        }

        if ("B".equalsIgnoreCase(eventType)) {
            String fullPileId = id.startsWith("F") ? "PILE-F" + id.substring(1) : "PILE-T" + id.substring(1);
            if (value == 0) {
                ChargingScheduleEngine.PILE_HEALTH.put(fullPileId, false);
                scheduleEngine.handlePileBreakdown(fullPileId);
                return logPrefix + "🚨 报警：物理充电桩 " + fullPileId + " 崩溃停机！启动全网受害车动态级联转场。";
            } else {
                ChargingScheduleEngine.PILE_HEALTH.put(fullPileId, true);
                scheduleEngine.autoDispatchWaitingArea();
                return logPrefix + "♻️ 恢复：物理充电桩 " + fullPileId + " 恢复健康，重新并网。";
            }
        }

        if ("C".equalsIgnoreCase(eventType)) {
            // 🎯 核心修复 3：修改指令和离场指令同样必须实施精确身份 ID 校验！
            UserAccount account = accountRepository.findByUsername(id).orElse(null);
            if (account == null)
                return logPrefix + "⚠️ 变更忽略：非法车主 " + id;
            Long userId = account.getId();

            List<Bill> bills = billRepository.findByUserId(userId);
            Bill activeBill = bills.stream().filter(b -> "CHARGING".equals(b.getStatus())).findFirst().orElse(null);
            if (activeBill == null)
                return logPrefix + "⚠️ 变更忽略：未找到该车主执行中的活动事务。";

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
                if (wasInPileQueue)
                    scheduleEngine.autoDispatchWaitingArea();
                return logPrefix + "🔌 取消：车主 " + id + " 剔除系统，车位物理释放，即时触发等候区级联调度。";
            }

            boolean currentIsFast = activeBill.getBillNumber().contains("-FAST-");
            boolean targetIsFast = "FAST".equalsIgnoreCase(chargeType) || "F".equalsIgnoreCase(chargeType);
            if (!"O".equalsIgnoreCase(chargeType) && (currentIsFast != targetIsFast)) {
                String currentPile = activeBill.getPileId();
                if (ChargingScheduleEngine.PILE_QUEUES.containsKey(currentPile)) {
                    ChargingScheduleEngine.PILE_QUEUES.get(currentPile).remove(activeBill.getBillNumber());
                }
                ChargingScheduleEngine.WAITING_AREA.remove(activeBill.getBillNumber());

                String newModeLabel = targetIsFast ? "-FAST-" : "-SLOW-";
                String oldNo = activeBill.getBillNumber();
                String newNo = oldNo.replace("-FAST-", newModeLabel).replace("-SLOW-", newModeLabel);

                activeBill.setBillNumber(newNo);
                activeBill.setPileId("PENDING");
                activeBill.setExpectedAmount(BigDecimal.valueOf(value));
                billRepository.save(activeBill);

                int insertPos = ChargingScheduleEngine.WAITING_AREA.size();
                for (int idx = 0; idx < ChargingScheduleEngine.WAITING_AREA.size(); idx++) {
                    String bNo = ChargingScheduleEngine.WAITING_AREA.get(idx);
                    Bill other = billRepository.findByBillNumber(bNo).orElse(null);
                    if (other != null && (other.getBillNumber().contains("-FAST-") == targetIsFast)) {
                        if (other.getStartTime().isAfter(activeBill.getStartTime())) {
                            insertPos = idx;
                            break;
                        }
                    }
                }
                ChargingScheduleEngine.WAITING_AREA.add(insertPos, newNo);
                scheduleEngine.autoDispatchWaitingArea();
                return logPrefix + "🔄 模式互切：车主 " + id + " 基于原始虚拟时间戳在目标等候区完成公平顺位挂载。";
            }

            BigDecimal newExpected = BigDecimal.valueOf(value);
            String currentPile = activeBill.getPileId();
            boolean isFirstChargingNode = false;
            if (ChargingScheduleEngine.PILE_QUEUES.containsKey(currentPile)
                    && !ChargingScheduleEngine.PILE_QUEUES.get(currentPile).isEmpty()) {
                if (ChargingScheduleEngine.PILE_QUEUES.get(currentPile).get(0).equals(activeBill.getBillNumber()))
                    isFirstChargingNode = true;
            }

            if (isFirstChargingNode && newExpected.compareTo(activeBill.getChargeAmount()) <= 0) {
                ChargingScheduleEngine.PILE_QUEUES.get(currentPile).remove(activeBill.getBillNumber());
                activeBill.setStatus("UNPAID");
                activeBill.setExpectedAmount(newExpected);
                billRepository.save(activeBill);
                scheduleEngine.autoDispatchWaitingArea();
                return logPrefix + "🛑 熔断：修改目标度数低于已充入度数，系统瞬间切断输出并开启结算。";
            } else {
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
        if (account.getBalance().compareTo(bill.getTotalFee()) < 0)
            throw new RuntimeException("❌ 微信清算失败：钱包余额不足！");
        account.setBalance(account.getBalance().subtract(bill.getTotalFee()));
        accountRepository.save(account);
        bill.setStatus("PAID");
        billRepository.save(bill);
        return "SUCCESS:" + bill.getTotalFee();
    }
}