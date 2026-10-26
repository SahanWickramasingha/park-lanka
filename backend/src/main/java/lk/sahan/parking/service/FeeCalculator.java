package lk.sahan.parking.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import lk.sahan.parking.domain.PricingPolicy;

/** Rates are per started minute, with a minimum charge of one minute. */
public final class FeeCalculator {
    private FeeCalculator() {}
    public record Charge(long minutes, BigDecimal amount) {}
    public record Breakdown(long elapsedSeconds,long billableMinutes,long billedUnits,String unit,BigDecimal rate,int graceMinutes,BigDecimal dailyCap,long dayBlocks,BigDecimal amount,String explanation){}
    public static Breakdown policyCharge(Instant start,Instant end,PricingPolicy policy){
        if(start==null||end==null||end.isBefore(start)||policy==null||policy.rate()==null||policy.rate().signum()<=0||!java.util.Set.of("MINUTE","HOUR").contains(policy.unit())||policy.graceMinutes()<0||policy.graceMinutes()>1440||policy.dailyCap()!=null&&policy.dailyCap().signum()<0)throw new IllegalArgumentException("Invalid pricing policy or range");
        Duration elapsed=Duration.between(start,end),grace=Duration.ofMinutes(policy.graceMinutes());long secondsPerUnit=policy.unit().equals("HOUR")?3600:60;
        if(policy.graceMinutes()>0&&elapsed.compareTo(grace)<=0)return new Breakdown(elapsed.getSeconds(),0,0,policy.unit(),policy.rate(),policy.graceMinutes(),policy.dailyCap(),elapsed.isZero()?0:ceil(elapsed,86400),BigDecimal.ZERO.setScale(2),"Within grace period; no charge");
        Duration billable=elapsed.minus(grace);long units=Math.max(policy.graceMinutes()==0?1:0,ceil(billable,secondsPerUnit));
        BigDecimal total=policy.rate().multiply(BigDecimal.valueOf(units));long blocks=Math.max(1,ceil(elapsed,86400));
        if(policy.dailyCap()!=null){
            long full=elapsed.dividedBy(Duration.ofDays(1));Duration remainder=elapsed.minusDays(full);total=BigDecimal.ZERO;
            if(full>0){total=cost(Duration.ofDays(1).minus(grace),policy,secondsPerUnit);if(full>1)total=total.add(cost(Duration.ofDays(1),policy,secondsPerUnit).multiply(BigDecimal.valueOf(full-1)));if(!remainder.isZero())total=total.add(cost(remainder,policy,secondsPerUnit));}
            else total=cost(billable,policy,secondsPerUnit);
        }
        return new Breakdown(elapsed.getSeconds(),Math.max(policy.graceMinutes()==0?1:0,ceil(billable,60)),units,policy.unit(),policy.rate(),policy.graceMinutes(),policy.dailyCap(),blocks,total.setScale(2,RoundingMode.HALF_UP),PricingService.describe(policy));
    }
    private static BigDecimal cost(Duration duration,PricingPolicy p,long seconds){long units=ceil(duration,seconds);if(duration.isZero()&&p.graceMinutes()==0)units=1;return p.rate().multiply(BigDecimal.valueOf(units)).min(p.dailyCap());}
    private static long ceil(Duration duration,long seconds){long floor=duration.dividedBy(Duration.ofSeconds(seconds));return floor+(duration.minusSeconds(floor*seconds).isZero()?0:1);}

    public static Charge charge(Instant start, Instant end, BigDecimal perMinute) {
        if (start == null || end == null || perMinute == null || end.isBefore(start) || perMinute.signum() <= 0)
            throw new IllegalArgumentException("Invalid time range or rate");
        Duration elapsed = Duration.between(start, end);
        long seconds = elapsed.getSeconds();
        long minutes = Math.max(1, seconds / 60 + (seconds % 60 != 0 || elapsed.getNano() != 0 ? 1 : 0));
        return new Charge(minutes, perMinute.multiply(BigDecimal.valueOf(minutes)).setScale(2, RoundingMode.HALF_UP));
    }
}
