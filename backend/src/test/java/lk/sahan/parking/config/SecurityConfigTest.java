package lk.sahan.parking.config;
import lk.sahan.parking.repo.CashierRepository;
import lk.sahan.parking.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SecurityConfigTest {
 private final SecurityConfig config=new SecurityConfig();
 private final CashierRepository cashiers=mock(CashierRepository.class);
 @Test void configuredAdminIsRequiredAndEmptyDatabaseCanStart(){
  for(String bad:List.of("", "${PARKING_ADMIN_PASSWORD}", "short"))
   assertThrows(IllegalArgumentException.class,()->config.users("admin",bad,config.passwordEncoder(),cashiers));
  var users=config.users("admin","admin-password",config.passwordEncoder(),cashiers);
  assertEquals("admin",users.loadUserByUsername("admin").getUsername());
  assertThrows(UsernameNotFoundException.class,()->users.loadUserByUsername("cashier"));
 }
 @Test void credentialsAreEncodedAndFreshPrincipalsSurviveErasure(){
  var users=config.users("admin","admin-password",config.passwordEncoder(),cashiers);
  String hash=users.loadUserByUsername("admin").getPassword();
  assertNotEquals("admin-password",hash);assertTrue(config.passwordEncoder().matches("admin-password",hash));
  ((User)users.loadUserByUsername("admin")).eraseCredentials();assertNotNull(users.loadUserByUsername("admin").getPassword());
 }
 @Test void adminCannotShadowAnExistingOrArchivedCashier(){
  when(cashiers.findAll()).thenReturn(List.of(new CashierAccount("admin","hash",new Branch("001","Main Branch"))));
  assertThrows(IllegalArgumentException.class,()->config.users("admin","admin-password",config.passwordEncoder(),cashiers));
 }
}
