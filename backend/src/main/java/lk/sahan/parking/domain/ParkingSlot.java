package lk.sahan.parking.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "parking_slots", uniqueConstraints = @UniqueConstraint(columnNames = "code"))
public class ParkingSlot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 16) private String code;
    @Column(nullable = false) private boolean occupied;
    // Nullable so Hibernate can add this column to databases from the previous release.
    @ManyToOne(fetch = FetchType.EAGER) private Branch branch;
    protected ParkingSlot() {}
    public ParkingSlot(String code) { this.code = code; }
    public ParkingSlot(String code, Branch branch) { this.code = code; this.branch = branch; }
    public Long getId() { return id; }
    public String getCode() { return code; }
    public boolean isOccupied() { return occupied; }
    public void setOccupied(boolean occupied) { this.occupied = occupied; }
    public Branch getBranch() { return branch; }
    public void setBranch(Branch branch) { this.branch = branch; }
}
