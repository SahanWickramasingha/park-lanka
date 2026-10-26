package lk.sahan.parking.service;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
public final class FinancialInputs {
 private FinancialInputs(){}
 public static BigDecimal money(BigDecimal value,String name){
  if(value==null||value.signum()<0||value.scale()>2||value.compareTo(new BigDecimal("9999999999.99"))>0)throw bad(name+" must be a nonnegative monetary amount with at most two decimals");
  return value.setScale(2,RoundingMode.UNNECESSARY);
 }
 public static String key(String key){try{if(key==null||!UUID.fromString(key).toString().equals(key))throw new IllegalArgumentException();return key;}catch(Exception ex){throw bad("A stable UUID requestKey is required");}}
 public static String hash(Object... values){try{var digest=MessageDigest.getInstance("SHA-256");for(Object value:values){byte[] bytes=String.valueOf(value).getBytes(StandardCharsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);}return HexFormat.of().formatHex(digest.digest());}catch(Exception ex){throw new IllegalStateException(ex);}}
 public static ResponseStatusException bad(String text){return new ResponseStatusException(HttpStatus.BAD_REQUEST,text);}
}
