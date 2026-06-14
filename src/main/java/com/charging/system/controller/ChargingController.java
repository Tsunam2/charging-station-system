package com.charging.system.controller;

import com.charging.system.entity.Bill;
import com.charging.system.service.ChargingScheduleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/charging")
public class ChargingController {

    @Autowired
    private ChargingScheduleService chargingScheduleService;

    // 🟩 接收前置设置的充电时间（分钟）参数
    @GetMapping("/apply")
    public String applyCharging(
            @RequestParam Long userId,
            @RequestParam String chargeType,
            @RequestParam Integer chargeMinutes) {
        return chargingScheduleService.applyForCharging(userId, chargeType, chargeMinutes);
    }

    @GetMapping("/status")
    public String getStatus(@RequestParam String billNumber) {
        Bill b = chargingScheduleService.getBillStatus(billNumber);
        if (b == null)
            return "NOT_FOUND";
        return b.getStatus() + ":" + b.getPileId() + ":" + b.getTotalFee();
    }

    @GetMapping("/stop")
    public String stopCharging(@RequestParam String billNumber) {
        try {
            chargingScheduleService.stopCharging(billNumber);
            return "SUCCESS";
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    @GetMapping("/pay")
    public String payBill(@RequestParam String billNumber) {
        try {
            return chargingScheduleService.payBill(billNumber);
        } catch (Exception e) {
            return e.getMessage();
        }
    }
}