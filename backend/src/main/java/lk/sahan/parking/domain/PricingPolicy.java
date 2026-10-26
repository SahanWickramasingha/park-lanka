package lk.sahan.parking.domain;
import java.math.BigDecimal;
public record PricingPolicy(BigDecimal rate,String unit,int graceMinutes,BigDecimal dailyCap) {}
