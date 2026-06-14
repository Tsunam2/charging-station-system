package com.charging.system.controller;

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

    /**
     * 🟩 专门应对现场验收的四元组用例注入网关入口
     * 样例：http://localhost:8080/api/charging/event?rawTuple=A,V1,F,60
     */
    @GetMapping("/event")
    public String injectEvaluationTuple(@RequestParam String rawTuple) {
        try {
            // 自动切割解析标准的 (A, V1, F, 60) 格式
            String[] tokens = rawTuple.replace("(", "").replace(")", "").split(",");
            if (tokens.length != 4) {
                return "❌ 格式非法！请输入类似 'A,V1,F,60' 的标准四元组。";
            }

            String eventType = tokens[0].trim();
            String id = tokens[1].trim();
            String chargeType = tokens[2].trim();
            Double value = Double.parseDouble(tokens[3].trim());

            return chargingScheduleService.processEvaluationEvent(eventType, id, chargeType, value);
        } catch (Exception e) {
            return "❌ 解析核心抛出异常: " + e.getMessage();
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