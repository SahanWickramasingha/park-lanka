package lk.sahan.parking.repo;
import lk.sahan.parking.domain.PricingRule;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface PricingRuleRepository extends JpaRepository<PricingRule,Long>{
 Optional<PricingRule> findByBranchIdAndVehicleType(Long branchId,String vehicleType);
 List<PricingRule> findByBranchIdOrderByVehicleType(Long branchId);
}
