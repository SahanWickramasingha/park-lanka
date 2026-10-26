package lk.sahan.parking.config;
import java.time.Clock;
import org.springframework.context.annotation.*;
@Configuration
public class TimeConfig {
    @Bean Clock applicationClock() { return Clock.systemUTC(); }
}
