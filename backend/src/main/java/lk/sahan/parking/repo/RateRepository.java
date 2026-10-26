package lk.sahan.parking.repo;

import lk.sahan.parking.domain.ParkingRate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateRepository extends JpaRepository<ParkingRate, Long> {}
