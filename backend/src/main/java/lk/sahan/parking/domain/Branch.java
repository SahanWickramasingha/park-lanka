package lk.sahan.parking.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "branches", uniqueConstraints = @UniqueConstraint(columnNames = "code"))
public class Branch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 8) private String code;
    @Column(nullable = false, length = 60) private String name;
    // SQL defaults also initialize pre-upgrade rows during Hibernate schema update.
    @Column(nullable = false, columnDefinition = "boolean default false") private boolean stopped;
    @Column(nullable = false, columnDefinition = "boolean default false") private boolean deleted;

    protected Branch() {}
    public Branch(String code, String name) { this.code = code; this.name = name; }
    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public boolean isStopped() { return stopped; }
    public void setStopped(boolean stopped) { this.stopped = stopped; }
    public boolean isDeleted() { return deleted; }
    public void archive() { deleted = true; stopped = true; }
    public String getStatus() { return deleted ? "Deleted" : stopped ? "Stopped" : "Active"; }
}
