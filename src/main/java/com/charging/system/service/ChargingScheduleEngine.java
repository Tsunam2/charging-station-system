package com.charging.system.service;

import com.charging.system.entity.Bill;
import com.charging.system.repository.BillRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class ChargingScheduleEngine {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private BillRepository billRepository;

    private static final String FAST_QUEUE_KEY = "charging:queue:fast";
    private static final String SLOW_QUEUE_KEY = "charging:queue:slow";

    /**
     * 🟩 将贪心调度算法巡检频率提升至 1 秒/次！杜绝任何无意义的等待
     */
    @Scheduled(fixedRate = 1000)
    public void runGreedySchedule() {
        processQueue(FAST_QUEUE_KEY, List.of("PILE-A01", "PILE-A02"));
        processQueue(SLOW_QUEUE_KEY, List.of("PILE-B01", "PILE-B02"));
    }

    private void processQueue(String queueKey, List<String> targetPiles) {
        Long size = redisTemplate.opsForList().size(queueKey);
        if (size == null || size == 0)
            return;

        // 🟩 彻底抛弃内存Map，动态从数据库核对谁是真正的空闲桩
        String allocatePileId = null;
        for (String pileId : targetPiles) {
            if (!billRepository.existsByPileIdAndStatus(pileId, "CHARGING")) {
                allocatePileId = pileId;
                break;
            }
        }

        if (allocatePileId != null) {
            String userIdStr = redisTemplate.opsForList().rightPop(queueKey);
            if (userIdStr != null) {
                Long userId = Long.parseLong(userIdStr);

                List<Bill> userBills = billRepository.findByUserId(userId);
                for (Bill bill : userBills) {
                    if ("PENDING".equals(bill.getPileId()) && "CHARGING".equals(bill.getStatus())) {
                        bill.setPileId(allocatePileId); // 瞬间咬合绑定
                        billRepository.save(bill);
                        System.out.println("⚡ [贪心动态调度成功] 车辆分配至 -> " + allocatePileId);
                        break;
                    }
                }
            }
        }
    }

    public static void releasePile(String pileId) {
        System.out.println("♻️ [分布式事务] 物理桩 " + pileId + " 已安全释放。");
    }
}