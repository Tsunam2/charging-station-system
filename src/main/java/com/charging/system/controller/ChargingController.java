package com.charging.system.controller;

import com.charging.system.entity.Bill;
import com.charging.system.entity.UserAccount;
import com.charging.system.repository.AccountRepository;
import com.charging.system.repository.BillRepository;
import com.charging.system.service.ChargingScheduleEngine;
import com.charging.system.service.ChargingScheduleService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ChargingController {

    @Autowired
    private ChargingScheduleService scheduleService;
    @Autowired
    private ChargingScheduleEngine scheduleEngine;
    @Autowired
    private BillRepository billRepository;
    @Autowired
    private AccountRepository accountRepository;

    @PostMapping("/charging/event")
    public ResponseEntity<String> receiveEvent(@RequestParam String rawTuple) {
        String[] parts = rawTuple.replace("(", "").replace(")", "").split(",");
        String msg = scheduleService.processEvaluationEvent(parts[0], parts[1], parts[2], Double.parseDouble(parts[3]));
        return ResponseEntity.ok(msg);
    }

    @PostMapping("/time/pause")
    public ResponseEntity<?> pauseTime() {
        ChargingScheduleEngine.isPaused = true;
        return ResponseEntity.ok("PAUSED");
    }

    @PostMapping("/time/resume")
    public ResponseEntity<?> resumeTime() {
        ChargingScheduleEngine.isPaused = false;
        return ResponseEntity.ok("RESUMED");
    }

    @PostMapping("/time/tick")
    public ResponseEntity<?> tickTime() {
        scheduleEngine.clockTickAndChargeProgress();
        return ResponseEntity.ok("TICKED");
    }

    @PostMapping("/time/upload-cases")
    public ResponseEntity<?> uploadCases(@RequestBody String text) {
        ChargingScheduleEngine.TEST_CASES.clear();
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty())
                continue;
            String[] parts = line.split("\\s+");
            if (parts.length >= 2) {
                ChargingScheduleEngine.TEST_CASES.add(new String[] { parts[0], parts[1] });
            }
        }
        return ResponseEntity.ok("UPLOAD_SUCCESS");
    }

    @PostMapping("/time/jump")
    public ResponseEntity<?> jumpTime(@RequestParam String target) {
        List<String> logs = scheduleEngine.executeBackendJump(target, scheduleService);
        return ResponseEntity.ok(logs);
    }

    @PostMapping("/system/reset")
    public ResponseEntity<?> resetSystem() {
        scheduleEngine.reset();
        billRepository.deleteAll();
        accountRepository.findAll().forEach(a -> {
            if (!"admin".equals(a.getUsername())) {
                a.setBalance(new BigDecimal("1000.00"));
                accountRepository.save(a);
            }
        });
        return ResponseEntity.ok("RESET_SUCCESS");
    }

    @GetMapping("/system/status")
    public ResponseEntity<?> getSystemStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("time", ChargingScheduleEngine.getSimTimeStr());
        status.put("isPaused", ChargingScheduleEngine.isPaused);
        status.put("waitArea", ChargingScheduleEngine.WAITING_AREA);

        // 🎯 核心修正：打包回传后端由事件注入及自然流逝产生的所有确定性日志流水
        status.put("logs", ChargingScheduleEngine.LIVE_LOGS);

        Map<String, Object> piles = new HashMap<>();
        for (String p : ChargingScheduleEngine.PILE_HEALTH.keySet()) {
            Map<String, Object> pileData = new HashMap<>();
            pileData.put("health", ChargingScheduleEngine.PILE_HEALTH.get(p));
            pileData.put("queue", ChargingScheduleEngine.PILE_QUEUES.get(p));
            piles.put(p, pileData);
        }
        status.put("piles", piles);

        Map<String, Object> cars = new HashMap<>();
        List<String> allActive = new ArrayList<>(ChargingScheduleEngine.WAITING_AREA);
        ChargingScheduleEngine.PILE_QUEUES.values().forEach(allActive::addAll);
        for (String billNo : allActive) {
            billRepository.findByBillNumber(billNo).ifPresent(b -> {
                Map<String, Object> detail = new HashMap<>();
                detail.put("userId", "V" + b.getUserId());
                detail.put("type", b.getBillNumber().contains("-FAST-") ? "F" : "T");
                detail.put("req", b.getExpectedAmount());
                detail.put("charged", b.getChargeAmount());
                detail.put("cost", b.getTotalFee());
                cars.put(billNo, detail);
            });
        }
        status.put("cars", cars);
        return ResponseEntity.ok(status);
    }

    @GetMapping("/charging/my-bills")
    public ResponseEntity<?> getMyBills(HttpSession session) {
        UserAccount user = (UserAccount) session.getAttribute("user");
        if (user == null)
            return ResponseEntity.status(401).build();
        return ResponseEntity.ok(billRepository.findByUserId(user.getId()));
    }

    @GetMapping("/charging/all-bills")
    public ResponseEntity<?> getAllBills(HttpSession session) {
        UserAccount user = (UserAccount) session.getAttribute("user");
        if (user == null || !"ADMIN".equals(user.getRole()))
            return ResponseEntity.status(401).build();
        return ResponseEntity.ok(billRepository.findAll());
    }

    @PostMapping("/charging/pay")
    public ResponseEntity<?> payBill(@RequestParam String billNumber) {
        try {
            return ResponseEntity.ok(Collections.singletonMap("msg", scheduleService.payBill(billNumber)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("msg", e.getMessage()));
        }
    }
}