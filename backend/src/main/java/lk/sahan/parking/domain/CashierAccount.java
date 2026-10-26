package lk.sahan.parking.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "cashier_accounts", uniqueConstraints = @UniqueConstraint(columnNames = "username"))
public class CashierAccount {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 32) private String username;
    @Column(nullable = false, length = 100) private String passwordHash;
    @ManyToOne(optional = false, fetch = FetchType.EAGER) private Branch branch;
    @Column(nullable = false) private boolean enabled = true;
    @Column(nullable = false, columnDefinition = "boolean default false") private boolean deleted;

    @Column(length = 160, unique = true) private String legacyOwnerKey;

    public String ownerKey() { return legacyOwnerKey == null ? "cashier:" + id : legacyOwnerKey; }
    public String getLegacyOwnerKey() { return legacyOwnerKey; }
    public void linkLegacyOwner(String key) { this.legacyOwnerKey = key; }

    protected CashierAccount() {}
    public CashierAccount(String username, String passwordHash, Branch branch) {
        this.username = username; this.passwordHash = passwordHash; this.branch = branch;
    }
    public Long getId() { return id; }
    public String getUsername() { return username; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getPasswordHash() { return passwordHash; }
    public Branch getBranch() { return branch; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public boolean isDeleted() { return deleted; }
    public void archive() { deleted = true; enabled = false; }
    public boolean canAccess() { return enabled && !deleted && !branch.isDeleted(); }
    public String getStatus() {
        return deleted ? "Removed" : branch.isDeleted() ? "Branch Deleted" : enabled ? "Active" : "Stopped";
    }
}
