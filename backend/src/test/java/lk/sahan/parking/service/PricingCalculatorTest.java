package lk.sahan.parking.service;
import lk.sahan.parking.domain.PricingPolicy;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
class PricingCalculatorTest {
 private static final Instant START=Instant.parse("2026-10-21T18:29:30Z");
 BigDecimal amount(long seconds,String unit,int grace,String cap){return FeeCalculator.policyCharge(START,START.plusSeconds(seconds),new PricingPolicy(new BigDecimal("2.00"),unit,grace,cap==null?null:new BigDecimal(cap))).amount();}
 @Test void startedUnitsGraceAndMidnightAreUnambiguous(){
  assertEquals(new BigDecimal("2.00"),amount(0,"MINUTE",0,null));assertEquals(new BigDecimal("2.00"),amount(60,"MINUTE",0,null));assertEquals(new BigDecimal("4.00"),amount(61,"MINUTE",0,null));
  assertEquals(new BigDecimal("2.00"),amount(3600,"HOUR",0,null));assertEquals(new BigDecimal("4.00"),amount(3601,"HOUR",0,null));
  assertEquals(new BigDecimal("0.00"),amount(300,"MINUTE",5,null));assertEquals(new BigDecimal("2.00"),amount(301,"MINUTE",5,null));assertEquals(new BigDecimal("4.00"),amount(361,"MINUTE",5,null));
  // START is 23:59:30 in Colombo; crossing midnight adds only actual elapsed time.
  assertEquals(new BigDecimal("4.00"),amount(61,"MINUTE",0,null));
  var nano=FeeCalculator.policyCharge(START,START.plusSeconds(60).plusNanos(1),new PricingPolicy(new BigDecimal("2.00"),"MINUTE",0,null));assertEquals(new BigDecimal("4.00"),nano.amount());
 }
 @Test void capsApplyToEachConsecutiveEntryAnchored24HourBlock(){
  assertEquals(new BigDecimal("100.00"),amount(86400,"MINUTE",0,"100.00"));assertEquals(new BigDecimal("102.00"),amount(86401,"MINUTE",0,"100.00"));
  assertEquals(new BigDecimal("200.00"),amount(172800,"MINUTE",0,"100.00"));assertEquals(new BigDecimal("202.00"),amount(172801,"MINUTE",0,"100.00"));
  assertEquals(new BigDecimal("0.00"),amount(86400,"HOUR",1440,"100.00"));assertEquals(new BigDecimal("2.00"),amount(86401,"HOUR",1440,"100.00"));
  assertEquals(new BigDecimal("0.00"),amount(99999,"MINUTE",0,"0.00"));
 }
}
