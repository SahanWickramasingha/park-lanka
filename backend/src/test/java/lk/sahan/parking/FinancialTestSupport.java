package lk.sahan.parking;
import java.util.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
final class FinancialTestSupport {
 private static final Map<String,String> attemptQuotes=new java.util.concurrent.ConcurrentHashMap<>();
 /** Normal clients fetch a server quote once. Preserve an existing quote/key through retries. */
 static Object quoted(MockMvc mvc,com.fasterxml.jackson.databind.ObjectMapper json,String user,String password,org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder route,Object data)throws Exception{
  String path=route.buildRequest(new org.springframework.mock.web.MockServletContext()).getRequestURI();
  if(!path.matches("/api/sessions/[0-9]+/checkout")||!(data instanceof Map<?,?> map)||map.containsKey("quoteId"))return data;
  String attempt=user+"|"+map.get("requestKey");var copy=new HashMap<Object,Object>(map);
  if(attemptQuotes.containsKey(attempt)){copy.put("quoteId",attemptQuotes.get(attempt));return copy;}
  var response=mvc.perform(get(path.replace("/checkout","/quote")).with(httpBasic(user,password))).andReturn().getResponse();
  if(response.getStatus()!=200)return data;
  String quote=json.readTree(response.getContentAsString()).path("quoteId").asText();attemptQuotes.putIfAbsent(attempt,quote);copy.put("quoteId",attemptQuotes.get(attempt));return copy;
 }
 static String cashJson(MockMvc mvc,long id,String user,String password)throws Exception{var json=new com.fasterxml.jackson.databind.ObjectMapper();return json.writeValueAsString(quoted(mvc,json,user,password,post("/api/sessions/"+id+"/checkout"),cash()));}
 static Map<String,Object> cash(){return Map.of("method","CASH","requestKey",UUID.randomUUID().toString(),"confirmedAmount","1.00","cashTendered","1.00");}
 static String cashJson(){return "{\"method\":\"CASH\",\"requestKey\":\""+UUID.randomUUID()+"\",\"confirmedAmount\":1.00,\"cashTendered\":1.00}";}
 static void openShift(MockMvc mvc,String user,String password)throws Exception{
  var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
  var current=mvc.perform(get("/api/shifts/current").with(httpBasic(user,password))).andExpect(status().isOk()).andReturn();
  if(!mapper.readTree(current.getResponse().getContentAsString()).path("active").asBoolean())
   mvc.perform(post("/api/shifts").with(httpBasic(user,password)).contentType("application/json").content("{\"requestKey\":\""+UUID.randomUUID()+"\",\"openingCash\":0.00}")).andExpect(status().isOk());
 }
}
