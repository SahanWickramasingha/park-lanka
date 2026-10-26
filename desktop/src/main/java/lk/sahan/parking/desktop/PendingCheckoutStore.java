package lk.sahan.parking.desktop;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.prefs.Preferences;
/** Local recovery metadata only, no credentials. All calls run on the IO executor. */
final class PendingCheckoutStore {
 private final Preferences prefs;private final ObjectMapper json=new ObjectMapper();
 PendingCheckoutStore(ApiClient api){this(api,"checkout");}
 PendingCheckoutStore(ApiClient api,String kind){try{if(!Set.of("checkout","correction").contains(kind))throw new IllegalArgumentException("Invalid recovery namespace");String scope=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((api.baseUrl()+"|"+api.username()).getBytes(StandardCharsets.UTF_8)));prefs=Preferences.userRoot().node("lk/sahan/parking/"+kind+"/"+scope);}catch(Exception e){throw new IllegalStateException(e);}}
 Map<String,String> get(long ticket)throws Exception{String value=prefs.get("ticket-"+ticket,null);return value==null?null:json.readValue(value,new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){});}
 void save(long ticket,Map<String,String> request)throws Exception{prefs.put("ticket-"+ticket,json.writeValueAsString(request));prefs.flush();}
 void clear(long ticket)throws Exception{prefs.remove("ticket-"+ticket);prefs.flush();}
 List<Long> tickets()throws Exception{return Arrays.stream(prefs.keys()).filter(k->k.startsWith("ticket-")).map(k->Long.parseLong(k.substring(7))).sorted().toList();}
}
