package lk.sahan.parking.service;
import lk.sahan.parking.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
/** Preserve pre-upgrade broadcast inbox/read state in the unified notification center. */
@Service
public class BroadcastNoticeMigration {
    private final BroadcastRepository broadcasts; private final EventLog events; private final BranchService branches;
    public BroadcastNoticeMigration(BroadcastRepository broadcasts,EventLog events,BranchService branches) { this.broadcasts=broadcasts; this.events=events; this.branches=branches; }
    @Bean CommandLineRunner migrateBroadcastNotices(BroadcastNoticeMigration migration) { return args->migration.migrate(); }
    @Transactional public void migrate() { if(broadcasts.count()==0)return; branches.lock(branches.main().getId()); int page=0; org.springframework.data.domain.Page<lk.sahan.parking.domain.Broadcast> batch; do { batch=broadcasts.findAll(org.springframework.data.domain.PageRequest.of(page++,100,org.springframework.data.domain.Sort.by("id"))); batch.forEach(events::broadcastDelivery); } while(batch.hasNext()); }
}
