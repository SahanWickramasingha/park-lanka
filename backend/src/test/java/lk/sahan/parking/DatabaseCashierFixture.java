package lk.sahan.parking;
import lk.sahan.parking.domain.CashierAccount;
import lk.sahan.parking.repo.CashierRepository;
import lk.sahan.parking.service.BranchService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
/** Test-only replacement for the removed environment login. Never packaged. */
public class DatabaseCashierFixture extends AbstractTestExecutionListener {
 @Override public int getOrder(){return 2500;}
 @Override public void beforeTestMethod(TestContext test){
  var ctx=test.getApplicationContext();var env=ctx.getEnvironment();
  String name=env.getProperty("test.cashier.username");
  if(name==null)return;
  var accounts=ctx.getBean(CashierRepository.class);
  if(accounts.findByUsername(name).isEmpty()){
   var account=new CashierAccount(name,ctx.getBean(PasswordEncoder.class).encode(env.getRequiredProperty("test.cashier.password")),ctx.getBean(BranchService.class).main());
   account.linkLegacyOwner("bootstrap:"+name);
   accounts.saveAndFlush(account);
  }
 }
}
