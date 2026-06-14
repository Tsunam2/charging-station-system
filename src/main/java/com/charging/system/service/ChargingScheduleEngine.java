package com.charging.system.service;

import com.charging.system.entity.Bill;
import com.charging.system.repository.BillRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class ChargingScheduleEngine {

    @Autowired
    private BillRepository billRepository;

    private static LocalTime simTime = LocalTime.of(6, 0);

    public static final Map<String, Boolean> PILE_HEALTH = new ConcurrentHashMap<>();
    public static final Map<String, List<String>> PILE_QUEUES = new ConcurrentHashMap<>();
    public static final List<String> WAITING_AREA = new CopyOnWriteArrayList<>();

    static {
        PILE_HEALTH.put("PILE-F1", true);
        PILE_HEALTH.put("PILE-F2", true);
        PILE_HEALTH.put("PILE-T1", true);
        PILE_HEALTH.put("PILE-T2", true);
        PILE_HEALTH.put("PILE-T3", true);

        PILE_QUEUES.put("PILE-F1", new ArrayList<>());
        PILE_QUEUES.put("PILE-F2", new ArrayList<>());
        PILE_QUEUES.put("PILE-T1", new ArrayList<>());
        PILE_QUEUES.put("PILE-T2", new ArrayList<>());
        PILE_QUEUES.put("PILE-T3", new ArrayList<>());
    }

    public static String getSimTimeStr() {
        return simTime.toString();
    }

    /**
     * 步进高频核算时钟：支持无限期全量收尾直至彻底空闲清零
     */
    @Scheduled(fixedRate = 6000)
    public void clockTickAndChargeProgress() {
        boolean allQueuesEmpty = PILE_QUEUES.values().stream().allMatch(List::isEmpty);
        boolean isAllCleared = WAITING_AREA.isEmpty() && allQueuesEmpty;

        // 跨过运营截止期 且 全场无滞留车辆 ➔ 终止时钟
        if (simTime.isAfter(LocalTime.of(11, 0)) && isAllCleared)
            return;

        // 1. 优先充能
        for (String pileId : PILE_QUEUES.keySet()) {
            if (!PILE_HEALTH.get(pileId))
                continue;
            List<String> queue = PILE_QUEUES.get(pileId);
            if (!queue.isEmpty()) {
                executeOneMinuteCharging(queue.get(0), pileId);
            }
        }

        // 2. 步进虚拟分钟（纠正时序，保障临界点计费精准）
        simTime = simTime.plusMinutes(1);

        // 3. 释放叫号总线
        boolean checkBreakdownLock = PILE_HEALTH.values().stream().anyMatch(h -> !h);
        if (!checkBreakdownLock)
            autoDispatchWaitingArea();
    }

    private void executeOneMinuteCharging(String billNo, String pileId) {
        Optional<Bill> billOpt = billRepository.findByBillNumber(billNo);
        if (billOpt.isEmpty() || !"CHARGING".equals(billOpt.get().getStatus()))
            return;
        Bill bill = billOpt.get();

        BigDecimal powerPerMinute = pileId.contains("-F")
                ? new BigDecimal("0.5")
                : BigDecimal.valueOf(10).divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);

        BigDecimal electricPrice = BigDecimal.valueOf(0.7);
        if (simTime.isBefore(LocalTime.of(7, 0)))
            electricPrice = BigDecimal.valueOf(0.4);
        if (!simTime.isBefore(LocalTime.of(10, 0)))
            electricPrice = BigDecimal.valueOf(1.0);

        BigDecimal currentTotalUnitPrice = electricPrice.add(BigDecimal.valueOf(0.8));

        BigDecimal expected = bill.getExpectedAmount();
        BigDecimal current = bill.getChargeAmount();

        if (current.compareTo(expected) < 0) {
            BigDecimal newAmount = current.add(powerPerMinute);
            BigDecimal addedFee = powerPerMinute.multiply(currentTotalUnitPrice);

            if (newAmount.compareTo(expected) >= 0) {
                BigDecimal diff = expected.subtract(current);
                addedFee = diff.multiply(currentTotalUnitPrice);
                newAmount = expected;
                PILE_QUEUES.get(pileId).remove(billNo); // 充满自动物理出队拔枪
                bill.setStatus("UNPAID");
            }

            bill.setChargeAmount(newAmount.setScale(2, RoundingMode.HALF_UP));
            bill.setTotalFee(bill.getTotalFee().add(addedFee).setScale(2, RoundingMode.HALF_UP));
            billRepository.save(bill);
        }
    }

    /**
     * 🟩 需求三数学原理：计算桩预期等待耗时 E[T_wait]
     */
    private BigDecimal calculateExpectedWaitingTime(String pileId) {
        List<String> queue = PILE_QUEUES.get(pileId);
        if (queue.isEmpty())
            return BigDecimal.ZERO;

        BigDecimal totalRemainingEnergy = BigDecimal.ZERO;

        // 1. T_rem_active
        Optional<Bill> activeOpt = billRepository.findByBillNumber(queue.get(0));
        if (activeOpt.isPresent()) {
            BigDecimal rem = activeOpt.get().getExpectedAmount().subtract(activeOpt.get().getChargeAmount());
            if (rem.compareTo(BigDecimal.ZERO) > 0)
                totalRemainingEnergy = totalRemainingEnergy.add(rem);
        }

        // 2. 后续排队总电量耗时叠加
        for (int i = 1; i < queue.size(); i++) {
            Optional<Bill> bOpt = billRepository.findByBillNumber(queue.get(i));
            if (bOpt.isPresent())
                totalRemainingEnergy = totalRemainingEnergy.add(bOpt.get().getExpectedAmount());
        }

        BigDecimal power = pileId.contains("-F") ? new BigDecimal("0.5")
                : BigDecimal.valueOf(10).divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
        return totalRemainingEnergy.divide(power, 4, RoundingMode.HALF_UP);
    }

    public void autoDispatchWaitingArea() {
        if (WAITING_AREA.isEmpty())
            return;

        for (int i = 0; i < WAITING_AREA.size(); i++) {
            String billNo = WAITING_AREA.get(i);
            Bill bill = billRepository.findByBillNumber(billNo).orElse(null);
            if (bill == null)
                continue;

            String typePrefix = bill.getBillNumber().contains("-FAST-") ? "PILE-F" : "PILE-T";
            String targetPile = findOptimalPile(typePrefix);

            if (targetPile != null) {
                WAITING_AREA.remove(i);
                i--;
                PILE_QUEUES.get(targetPile).add(billNo);
                bill.setPileId(targetPile);
                billRepository.save(bill);
            }
        }
    }

    private String findOptimalPile(String prefix) {
        String bestPile = null;
        BigDecimal minWaitTime = BigDecimal.valueOf(Double.MAX_VALUE);

        List<String> sortedKeys = new ArrayList<>(PILE_QUEUES.keySet());
        Collections.sort(sortedKeys);

        for (String pileId : sortedKeys) {
            if (pileId.startsWith(prefix) && PILE_HEALTH.get(pileId)) {
                List<String> queue = PILE_QUEUES.get(pileId);
                if (queue.size() < 3) { // 满足 M < 3 刚性物理车位屏障
                    BigDecimal waitTime = calculateExpectedWaitingTime(pileId);
                    if (waitTime.compareTo(minWaitTime) < 0) {
                        minWaitTime = waitTime;
                        bestPile = pileId;
                    } else if (waitTime.compareTo(minWaitTime) == 0) {
                        if (bestPile == null || queue.size() < PILE_QUEUES.get(bestPile).size()) {
                            bestPile = pileId;
                        }
                    }
                }
            }
        }
        return bestPile;
    }

    /**
     * 🟩 需求四：突发故障“4级阶梯物理槽位分配流转与最高特权逼退算法”
     */
    public void handlePileBreakdown(String brokenPileId) {
        PILE_HEALTH.put(brokenPileId, false);
        List<String> brokenQueue = PILE_QUEUES.get(brokenPileId);
        if (brokenQueue.isEmpty())
            return;

        String typePrefix = brokenPileId.contains("-F") ? "PILE-F" : "PILE-T";

        // 严格按照原始请求时间戳（startTime）由早到晚正序排列受害车辆
        List<Bill> victimBills = new ArrayList<>();
        for (String bNo : brokenQueue) {
            billRepository.findByBillNumber(bNo).ifPresent(victimBills::add);
        }
        victimBills.sort(Comparator.comparing(Bill::getStartTime));
        brokenQueue.clear();

        List<String> sortedKeys = new ArrayList<>(PILE_QUEUES.keySet());
        Collections.sort(sortedKeys);

        List<String> unmigratedVictims = new ArrayList<>();

        for (Bill bill : victimBills) {
            String billNo = bill.getBillNumber();
            boolean migrated = false;

            // 🥇 第一级：瞬时强电接入（健康桩空闲 len == 0）
            for (String pId : sortedKeys) {
                if (pId.startsWith(typePrefix) && !pId.equals(brokenPileId) && PILE_HEALTH.get(pId)) {
                    if (PILE_QUEUES.get(pId).isEmpty()) {
                        executeDirectInsert(pId, 0, billNo, bill);
                        migrated = true;
                        break;
                    }
                }
            }
            if (migrated)
                continue;

            // 🥈 第二级：首位排队锁定（健康桩只有1车充电且无排队 len == 1）
            for (String pId : sortedKeys) {
                if (pId.startsWith(typePrefix) && !pId.equals(brokenPileId) && PILE_HEALTH.get(pId)) {
                    if (PILE_QUEUES.get(pId).size() == 1) {
                        executeDirectInsert(pId, 1, billNo, bill);
                        migrated = true;
                        break;
                    }
                }
            }
            if (migrated)
                continue;

            // 🥉 第三级：常规顺序顺延（健康桩1车充电，1车排队 len == 2）
            for (String pId : sortedKeys) {
                if (pId.startsWith(typePrefix) && !pId.equals(brokenPileId) && PILE_HEALTH.get(pId)) {
                    if (PILE_QUEUES.get(pId).size() == 2) {
                        executeDirectInsert(pId, 2, billNo, bill);
                        migrated = true;
                        break;
                    }
                }
            }
            if (migrated)
                continue;

            // 🚏 第四级：系统级最高优先级逼退（全场同类桩 M=3 全满载）
            unmigratedVictims.add(billNo);
        }

        // 强推回等候区绝对最头部（Index 0），并反向注入确保在等候区头部仍完美对齐原始时间戳顺序
        if (!unmigratedVictims.isEmpty()) {
            for (int j = unmigratedVictims.size() - 1; j >= 0; j--) {
                String bNo = unmigratedVictims.get(j);
                WAITING_AREA.add(0, bNo);
                billRepository.findByBillNumber(bNo).ifPresent(b -> {
                    b.setPileId("PENDING");
                    billRepository.save(b);
                });
            }
        }

        autoDispatchWaitingArea(); // 唤醒叫号总线
    }

    private void executeDirectInsert(String pileId, int index, String billNo, Bill bill) {
        // 遇故障迁移的车只用阶梯顺延或进入排队一号位，不用抢占当前健康桩上正在充电的0号位车
        int realIndex = (index == 0 && !PILE_QUEUES.get(pileId).isEmpty()) ? 1 : index;
        PILE_QUEUES.get(pileId).add(realIndex, billNo);
        bill.setPileId(pileId);
        billRepository.save(bill);
    }
}