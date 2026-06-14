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

    // 1:10 比例尺仿真时钟：起始时间 06:00
    private static LocalTime simTime = LocalTime.of(6, 0);

    // 内存维护充电桩硬件状态表
    public static final Map<String, Boolean> PILE_HEALTH = new ConcurrentHashMap<>();
    // 维护每个桩的排队队列（最多3个元素）
    public static final Map<String, List<String>> PILE_QUEUES = new ConcurrentHashMap<>();
    // 维护等候区队列（最多10个元素）
    public static final List<String> WAITING_AREA = new CopyOnWriteArrayList<>();

    static {
        // 初始化2个快充桩，3个慢充桩
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
     * 🟩 核心高频时钟步进核心：每现实1秒 = 模拟10秒。由于是按分钟计费，我们让它每现实6秒步进1条虚拟分钟
     */
    @Scheduled(fixedRate = 6000)
    public void clockTickAndChargeProgress() {
        if (simTime.isAfter(LocalTime.of(11, 0))) {
            return; // 运营时间 06:00 - 11:00 截止
        }
        simTime = simTime.plusMinutes(1); // 虚拟时间前进一步

        // 遍历所有充电桩，为处于各个桩队列第一位的（正在充电的）车辆注入能量并累算实时电费
        for (String pileId : PILE_QUEUES.keySet()) {
            if (!PILE_HEALTH.get(pileId))
                continue; // 故障桩不工作

            List<String> queue = PILE_QUEUES.get(pileId);
            if (!queue.isEmpty()) {
                String activeBillNo = queue.get(0); // 队首正在充电
                executeOneMinuteCharging(activeBillNo, pileId);
            }
        }

        // 扫描调度：如果没有任何桩处于故障转移锁定状态，则正常搬运等候区的车辆
        boolean checkBreakdownLock = PILE_HEALTH.values().stream().anyMatch(h -> !h);
        if (!checkBreakdownLock) {
            autoDispatchWaitingArea();
        }
    }

    /**
     * 逐分钟高精度多费率累产计算器
     */
    private void executeOneMinuteCharging(String billNo, String pileId) {
        Optional<Bill> billOpt = billRepository.findByBillNumber(billNo);
        if (billOpt.isEmpty())
            return;
        Bill bill = billOpt.get();

        if (!"CHARGING".equals(bill.getStatus()))
            return;

        // 根据快慢充桩类型，计算1分钟冲入的度数
        BigDecimal powerPerMinute = pileId.contains("-F")
                ? BigDecimal.valueOf(30).divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP) // 快充 0.5 度/分
                : BigDecimal.valueOf(10).divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP); // 慢充 0.1667 度/分

        // 判定当前虚拟时间下的精确费率矩阵
        BigDecimal electricPrice = BigDecimal.valueOf(0.7); // 默认平时
        if (simTime.isBefore(LocalTime.of(7, 0)))
            electricPrice = BigDecimal.valueOf(0.4); // 谷时
        if (simTime.isAfter(LocalTime.of(10, 0)) && simTime.isBefore(LocalTime.of(11, 1)))
            electricPrice = BigDecimal.valueOf(1.0); // 峰时

        BigDecimal servicePrice = BigDecimal.valueOf(0.8); // 固定服务费
        BigDecimal currentTotalUnitPrice = electricPrice.add(servicePrice);

        // 递增算费
        BigDecimal newAmount = bill.getChargeAmount().add(powerPerMinute);
        BigDecimal newElectricFee = bill.getElectricFee().add(powerPerMinute.multiply(electricPrice));
        BigDecimal newServiceFee = bill.getServiceFee().add(powerPerMinute.multiply(servicePrice));
        BigDecimal newTotalFee = bill.getTotalFee().add(powerPerMinute.multiply(currentTotalUnitPrice));

        bill.setChargeAmount(newAmount.setScale(2, RoundingMode.HALF_UP));
        bill.setElectricFee(newElectricFee.setScale(2, RoundingMode.HALF_UP));
        bill.setServiceFee(newServiceFee.setScale(2, RoundingMode.HALF_UP));
        bill.setTotalFee(newTotalFee.setScale(2, RoundingMode.HALF_UP));

        // 如果已经达到了车主预设的度数，自动终止充电切换为待支付
        if (bill.getChargeAmount().compareTo(BigDecimal.valueOf(100)) >= 0) { // 假设100或动态限制
            // 正常结束由外部或前端触发，这里持续自增直至上限
        }
        billRepository.save(bill);
    }

    /**
     * 将车辆由等候区推入桩排队队列
     */
    public void autoDispatchWaitingArea() {
        if (WAITING_AREA.isEmpty())
            return;

        for (String billNo : WAITING_AREA) {
            Bill bill = billRepository.findByBillNumber(billNo).orElse(null);
            if (bill == null)
                continue;

            String typePrefix = bill.getBillNumber().contains("-FAST-") ? "PILE-F" : "PILE-T";
            String targetPile = findOptimalPile(typePrefix);

            if (targetPile != null) {
                WAITING_AREA.remove(billNo);
                PILE_QUEUES.get(targetPile).add(billNo);
                bill.setPileId(targetPile);
                billRepository.save(bill);
                System.out.println("⚡ [等候区调度] 车辆 " + bill.getUserId() + " 成功移入 " + targetPile);
            }
        }
    }

    /**
     * 寻找当前队列长度未满3的最空闲同类型充电桩
     */
    private String findOptimalPile(String prefix) {
        String bestPile = null;
        int minSize = 3; // 队列上限为3

        for (Map.Entry<String, List<String>> entry : PILE_QUEUES.entrySet()) {
            String pileId = entry.getKey();
            if (pileId.startsWith(prefix) && PILE_HEALTH.get(pileId)) {
                int currentSize = entry.getValue().size();
                if (currentSize < minSize) {
                    minSize = currentSize;
                    bestPile = pileId;
                }
            }
        }
        return bestPile;
    }

    /**
     * 🛑 核心难点：充电桩突发损坏，执行最高优先级插队迁移算法
     */
    public void handlePileBreakdown(String brokenPileId) {
        PILE_HEALTH.put(brokenPileId, false);
        List<String> brokenQueue = PILE_QUEUES.get(brokenPileId);
        if (brokenQueue.isEmpty())
            return;

        System.out.println("🚨 [硬件报警] 充电桩 " + brokenPileId + " 损坏！等候区停止调度，启动最高优先级迁移！");

        String typePrefix = brokenPileId.contains("-F") ? "PILE-F" : "PILE-T";
        List<String> copyList = new ArrayList<>(brokenQueue);
        brokenQueue.clear();

        // 优先将损坏桩里的车辆插队转移到其他健康的同类型桩中
        for (String billNo : copyList) {
            boolean migrated = false;
            // 寻找其他未满的同类型桩
            for (String otherPileId : PILE_QUEUES.keySet()) {
                if (otherPileId.startsWith(typePrefix) && !otherPileId.equals(brokenPileId)
                        && PILE_HEALTH.get(otherPileId)) {
                    List<String> targetQ = PILE_QUEUES.get(otherPileId);
                    if (targetQ.size() < 3) {
                        // 强制插入到除了正在充电的第1位之外的次优先排队位置
                        int insertIndex = targetQ.isEmpty() ? 0 : 1;
                        targetQ.add(insertIndex, billNo);

                        Bill bill = billRepository.findByBillNumber(billNo).orElse(null);
                        if (bill != null) {
                            bill.setPileId(otherPileId);
                            billRepository.save(bill);
                        }
                        migrated = true;
                        System.out.println("♻️ [紧急流转] 车辆成功由故障桩转移插队至 -> " + otherPileId);
                        break;
                    }
                }
            }
            // 如果连其他同类型桩的排队位（M=3）也全挤满了，无处可去，只能以最高优先级逼退回等候区的最前方
            if (!migrated) {
                WAITING_AREA.add(0, billNo);
                Bill bill = billRepository.findByBillNumber(billNo).orElse(null);
                if (bill != null) {
                    bill.setPileId("PENDING");
                    billRepository.save(bill);
                }
            }
        }
    }
}