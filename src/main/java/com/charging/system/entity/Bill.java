package com.charging.system.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "t_bill")
public class Bill {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String billNumber;
    private Long userId;

    // 🎯 核心新增：显式记录车主账号（如 "V1", "V2"），彻底解决前端基于自增 userId 拼接导致的错位偏移问题
    @Column(name = "username")
    private String username;

    private String pileId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    // 强制 MySQL 开启 6 位小数高精度存储，防止计费精度溢出
    @Column(precision = 12, scale = 6)
    private BigDecimal chargeAmount;

    @Column(precision = 12, scale = 6)
    private BigDecimal expectedAmount;

    @Column(precision = 12, scale = 6)
    private BigDecimal valleyPower = BigDecimal.ZERO;

    @Column(precision = 12, scale = 6)
    private BigDecimal flatPower = BigDecimal.ZERO;

    @Column(precision = 12, scale = 6)
    private BigDecimal peakPower = BigDecimal.ZERO;

    @Column(precision = 12, scale = 6)
    private BigDecimal electricFee = BigDecimal.ZERO;

    @Column(precision = 12, scale = 6)
    private BigDecimal serviceFee = BigDecimal.ZERO;

    @Column(precision = 12, scale = 6)
    private BigDecimal totalFee = BigDecimal.ZERO;

    private String status;

    // 🎯 核心新增 1：记录车辆实际充了多少分钟，用于精准触发 10 分钟探针脉冲
    private int totalChargingMinutes = 0;

    // 🎯 核心新增 2：用于永久落盘存储“每 10 分钟快照脉冲”的集合列表，前端详单直接读取此字段
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "t_bill_details", joinColumns = @JoinColumn(name = "bill_id"))
    @Column(name = "detail_log")
    private List<String> detailLogs = new ArrayList<>();

    // --- Getters and Setters (完全保持不变，增加新字段的生成) ---
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBillNumber() {
        return billNumber;
    }

    public void setBillNumber(String billNumber) {
        this.billNumber = billNumber;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    // 🎯 对应新增的 Getter/Setter
    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPileId() {
        return pileId;
    }

    public void setPileId(String pileId) {
        this.pileId = pileId;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public BigDecimal getChargeAmount() {
        return chargeAmount;
    }

    public void setChargeAmount(BigDecimal chargeAmount) {
        this.chargeAmount = chargeAmount;
    }

    public BigDecimal getExpectedAmount() {
        return expectedAmount;
    }

    public void setExpectedAmount(BigDecimal expectedAmount) {
        this.expectedAmount = expectedAmount;
    }

    public BigDecimal getValleyPower() {
        return valleyPower;
    }

    public void setValleyPower(BigDecimal valleyPower) {
        this.valleyPower = valleyPower;
    }

    public BigDecimal getFlatPower() {
        return flatPower;
    }

    public void setFlatPower(BigDecimal flatPower) {
        this.flatPower = flatPower;
    }

    public BigDecimal getPeakPower() {
        return peakPower;
    }

    public void setPeakPower(BigDecimal peakPower) {
        this.peakPower = peakPower;
    }

    public BigDecimal getElectricFee() {
        return electricFee;
    }

    public void setElectricFee(BigDecimal electricFee) {
        this.electricFee = electricFee;
    }

    public BigDecimal getServiceFee() {
        return serviceFee;
    }

    public void setServiceFee(BigDecimal serviceFee) {
        this.serviceFee = serviceFee;
    }

    public BigDecimal getTotalFee() {
        return totalFee;
    }

    public void setTotalFee(BigDecimal totalFee) {
        this.totalFee = totalFee;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getTotalChargingMinutes() {
        return totalChargingMinutes;
    }

    public void setTotalChargingMinutes(int totalChargingMinutes) {
        this.totalChargingMinutes = totalChargingMinutes;
    }

    public List<String> getDetailLogs() {
        return detailLogs;
    }

    public void setDetailLogs(List<String> detailLogs) {
        this.detailLogs = detailLogs;
    }
}