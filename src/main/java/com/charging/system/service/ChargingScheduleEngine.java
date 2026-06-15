package com.charging.system.service;

import com.charging.system.entity.Bill;
import com.charging.system.repository.BillRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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

    @Autowired
    @Lazy
    private ChargingScheduleService scheduleService;

    private static LocalTime simTime = LocalTime.of(6, 0);
    public static boolean isPaused = false;
    private static LocalTime lastIngestedTime = LocalTime.of(5, 59);

    public static final List<String> LIVE_LOGS = new CopyOnWriteArrayList<>();
    public static final List<String[]> TEST_CASES = new CopyOnWriteArrayList<>();

    public static final Map<String, Boolean> PILE_HEALTH = new ConcurrentHashMap<>();
    public static final Map<String, List<String>> PILE_QUEUES = new ConcurrentHashMap<>();
    public static final List<String> WAITING_AREA = new CopyOnWriteArrayList<>();

    static {
        initPiles();
    }

    private static void initPiles() {
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

    public static LocalTime getSimTime() {
        return simTime;
    }

    public static String getSimTimeStr() {
        return simTime.toString();
    }

    @PostConstruct
    public void initLoadCases() {
        loadDefaultCases();
    }

    public void loadDefaultCases() {
        try {
            TEST_CASES.clear();
            ClassPathResource resource = new ClassPathResource("static/test_cases.txt");
            BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty())
                    continue;
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    TEST_CASES.add(new String[] { parts[0], parts[1] });
                }
            }
            System.out.println("🚀 [沙盒引擎] 已成功在后端缓存挂载 " + TEST_CASES.size() + " 条测试指令。");
        } catch (Exception e) {
            System.out.println("⚠️ [沙盒引擎] 未检测到默认静态 static/test_cases.txt 文件");
        }
    }

    public void reset() {
        simTime = LocalTime.of(6, 0);
        isPaused = true;
        WAITING_AREA.clear();
        LIVE_LOGS.clear();
        initPiles();
        billRepository.deleteAll();
        lastIngestedTime = LocalTime.of(5, 59);

        ingestEventsForSimTime();
        autoDispatchWaitingArea();
    }

    @Scheduled(fixedRate = 6000)
    public void autoTick() {
        if (!isPaused)
            clockTickAndChargeProgress();
    }

    public List<String> executeBackendJump(String targetTimeStr, ChargingScheduleService svc) {
        LocalTime targetTime = LocalTime.parse(targetTimeStr);

        if (targetTime.isBefore(LocalTime.of(6, 0))) {
            simTime = targetTime;
            isPaused = true;
            WAITING_AREA.clear();
            LIVE_LOGS.clear();
            initPiles();
            billRepository.deleteAll();
            lastIngestedTime = LocalTime.of(5, 59);
            return LIVE_LOGS;
        }

        reset();

        while (simTime.isBefore(targetTime)) {
            clockTickAndChargeProgress();
        }

        return LIVE_LOGS;
    }

    public void clockTickAndChargeProgress() {
        boolean allQueuesEmpty = PILE_QUEUES.values().stream().allMatch(List::isEmpty);
        boolean isAllCleared = WAITING_AREA.isEmpty() && allQueuesEmpty;
        if (simTime.isAfter(LocalTime.of(11, 0)) && isAllCleared)
            return;

        for (String pileId : PILE_QUEUES.keySet()) {
            if (!PILE_HEALTH.getOrDefault(pileId, true))
                continue;

            List<String> queue = PILE_QUEUES.get(pileId);
            if (!queue.isEmpty())
                executeOneMinuteCharging(queue.get(0), pileId);
        }

        simTime = simTime.plusMinutes(1);
        ingestEventsForSimTime();

        autoDispatchWaitingArea();
    }

    private void ingestEventsForSimTime() {
        if (!simTime.equals(lastIngestedTime)) {
            String timeStr = String.format("%02d:%02d", simTime.getHour(), simTime.getMinute());
            for (String[] tc : TEST_CASES) {
                if (tc[0].equals(timeStr)) {
                    String raw = tc[1];
                    String[] parts = raw.replace("(", "").replace(")", "").split(",");
                    String log = scheduleService.processEvaluationEvent(parts[0], parts[1], parts[2],
                            Double.parseDouble(parts[3]));
                    if (!LIVE_LOGS.contains(log)) {
                        LIVE_LOGS.add(log);
                    }
                }
            }
            lastIngestedTime = simTime;
        }
    }

    private void executeOneMinuteCharging(String billNo, String pileId) {
        Optional<Bill> billOpt = billRepository.findByBillNumber(billNo);
        if (billOpt.isEmpty() || !"CHARGING".equals(billOpt.get().getStatus()))
            return;
        Bill bill = billOpt.get();

        BigDecimal powerPerMinute = pileId.contains("-F") ? new BigDecimal("0.5")
                : BigDecimal.valueOf(10).divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP);

        BigDecimal electricPrice = BigDecimal.valueOf(1.0);
        String phaseLabel = "峰电段";
        if (simTime.isBefore(LocalTime.of(7, 0))) {
            electricPrice = BigDecimal.valueOf(0.4);
            phaseLabel = "谷电段";
        } else if (simTime.isBefore(LocalTime.of(10, 0))) {
            electricPrice = BigDecimal.valueOf(0.7);
            phaseLabel = "平电段";
        }

        BigDecimal currentTotalUnitPrice = electricPrice.add(BigDecimal.valueOf(0.8));

        BigDecimal expected = bill.getExpectedAmount();
        BigDecimal current = bill.getChargeAmount();

        if (current.compareTo(expected) < 0) {
            BigDecimal newAmount = current.add(powerPerMinute);
            BigDecimal actualPowerAdded = powerPerMinute;
            boolean isFinished = false;

            if (newAmount.compareTo(expected) >= 0) {
                actualPowerAdded = expected.subtract(current);
                newAmount = expected;
                String finishedLog = "【仿真时间: " + getSimTimeStr() + "】⚡ 车辆自适应满电离场核销通知: 单号 " + billNo;
                if (!LIVE_LOGS.contains(finishedLog))
                    LIVE_LOGS.add(finishedLog);

                PILE_QUEUES.get(pileId).remove(billNo);
                bill.setStatus("UNPAID");
                isFinished = true;
            }

            if (simTime.isBefore(LocalTime.of(7, 0)))
                bill.setValleyPower(bill.getValleyPower().add(actualPowerAdded));
            else if (simTime.isBefore(LocalTime.of(10, 0)))
                bill.setFlatPower(bill.getFlatPower().add(actualPowerAdded));
            else
                bill.setPeakPower(bill.getPeakPower().add(actualPowerAdded));

            BigDecimal addedFee = actualPowerAdded.multiply(currentTotalUnitPrice);
            bill.setChargeAmount(newAmount);
            bill.setTotalFee(bill.getTotalFee().add(addedFee));

            // 🎯 核心新增：10 分钟计费脉冲探针机制！
            bill.setTotalChargingMinutes(bill.getTotalChargingMinutes() + 1);
            int currentMins = bill.getTotalChargingMinutes();

            // 每当整整充能 10 分钟，或是车辆恰好满电时，生成并落盘一条极高精度的费用脉冲快照
            if (currentMins % 10 == 0 || isFinished) {
                if (bill.getDetailLogs() == null)
                    bill.setDetailLogs(new ArrayList<>());
                String pulseLog = String.format("⏱️ [%s] 持续作业 %d 分钟 (%s) | 已输出度数: %.2f 度 | 预估总额: ¥%.2f",
                        getSimTimeStr(), currentMins, phaseLabel, bill.getChargeAmount().doubleValue(),
                        bill.getTotalFee().doubleValue());
                bill.getDetailLogs().add(pulseLog);
            }

            billRepository.save(bill);
        }
    }

    private BigDecimal calculateExpectedWaitingTime(String pileId) {
        List<String> queue = PILE_QUEUES.get(pileId);
        if (queue.isEmpty())
            return BigDecimal.ZERO;

        BigDecimal totalRemainingEnergy = BigDecimal.ZERO;

        Optional<Bill> activeOpt = billRepository.findByBillNumber(queue.get(0));
        if (activeOpt.isPresent()) {
            BigDecimal rem = activeOpt.get().getExpectedAmount().subtract(activeOpt.get().getChargeAmount());
            if (rem.compareTo(BigDecimal.ZERO) > 0) {
                totalRemainingEnergy = totalRemainingEnergy.add(rem);
            }
        }

        for (int i = 1; i < queue.size(); i++) {
            Optional<Bill> bOpt = billRepository.findByBillNumber(queue.get(i));
            if (bOpt.isPresent()) {
                totalRemainingEnergy = totalRemainingEnergy.add(bOpt.get().getExpectedAmount());
            }
        }

        BigDecimal power = pileId.contains("-F") ? new BigDecimal("0.5")
                : BigDecimal.valueOf(10).divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP);
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
                String originalPile = bill.getPileId();
                if (!"PENDING".equals(originalPile) && PILE_QUEUES.containsKey(originalPile)) {
                    PILE_QUEUES.get(originalPile).remove(billNo);
                }
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
            if (pileId.startsWith(prefix) && PILE_HEALTH.getOrDefault(pileId, true)) {
                List<String> queue = PILE_QUEUES.get(pileId);
                if (queue.size() < 3) {
                    BigDecimal waitTime = calculateExpectedWaitingTime(pileId);
                    if (waitTime.compareTo(minWaitTime) < 0) {
                        minWaitTime = waitTime;
                        bestPile = pileId;
                    } else if (waitTime.compareTo(minWaitTime) == 0) {
                        if (bestPile == null || queue.size() < PILE_QUEUES.get(bestPile).size())
                            bestPile = pileId;
                    }
                }
            }
        }
        return bestPile;
    }

    public void handlePileBreakdown(String brokenPileId) {
        String typePrefix = brokenPileId.contains("-F") ? "PILE-F" : "PILE-T";
        List<String> brokenQueue = PILE_QUEUES.get(brokenPileId);
        if (brokenQueue.isEmpty())
            return;

        List<Bill> victimBills = new ArrayList<>();
        for (String bNo : brokenQueue)
            billRepository.findByBillNumber(bNo).ifPresent(victimBills::add);

        victimBills.sort(Comparator.comparing(Bill::getStartTime));
        brokenQueue.clear();

        List<String> sortedKeys = new ArrayList<>(PILE_QUEUES.keySet());
        Collections.sort(sortedKeys);
        List<String> unmigratedVictims = new ArrayList<>();

        for (Bill bill : victimBills) {
            String billNo = bill.getBillNumber();
            boolean migrated = false;
            for (String pId : sortedKeys) {
                if (pId.startsWith(typePrefix) && !pId.equals(brokenPileId) && PILE_HEALTH.getOrDefault(pId, true)
                        && PILE_QUEUES.get(pId).isEmpty()) {
                    executeDirectInsert(pId, 0, billNo, bill);
                    migrated = true;
                    break;
                }
            }
            if (migrated)
                continue;
            for (String pId : sortedKeys) {
                if (pId.startsWith(typePrefix) && !pId.equals(brokenPileId) && PILE_HEALTH.getOrDefault(pId, true)
                        && PILE_QUEUES.get(pId).size() == 1) {
                    executeDirectInsert(pId, 1, billNo, bill);
                    migrated = true;
                    break;
                }
            }
            if (migrated)
                continue;
            for (String pId : sortedKeys) {
                if (pId.startsWith(typePrefix) && !pId.equals(brokenPileId) && PILE_HEALTH.getOrDefault(pId, true)
                        && PILE_QUEUES.get(pId).size() == 2) {
                    executeDirectInsert(pId, 2, billNo, bill);
                    migrated = true;
                    break;
                }
            }
            if (migrated)
                continue;
            unmigratedVictims.add(billNo);
        }

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
        autoDispatchWaitingArea();
    }

    private void executeDirectInsert(String pileId, int index, String billNo, Bill bill) {
        int realIndex = (index == 0 && !PILE_QUEUES.get(pileId).isEmpty()) ? 1 : index;
        PILE_QUEUES.get(pileId).add(realIndex, billNo);
        bill.setPileId(pileId);
        billRepository.save(bill);
    }
}