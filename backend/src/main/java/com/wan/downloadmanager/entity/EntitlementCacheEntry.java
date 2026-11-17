package com.wan.downloadmanager.entity;

import jakarta.persistence.*;

/** Signed offline entitlement cache. */
@Entity
@Table(name = "entitlement_cache")
public class EntitlementCacheEntry {

    @Id
    @Column(name = "sub")
    private String sub;

    @Column(name = "tier")
    private String tier;
    @Column(name = "status")
    private String status;
    @Column(name = "roles")
    private String roles;
    @Column(name = "verified_at")
    private long verifiedAt;
    @Column(name = "expires_at")
    private long expiresAt;
    @Column(name = "signature")
    private String signature;

    public String getSub() { return sub; }
    public void setSub(String sub) { this.sub = sub; }
    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRoles() { return roles; }
    public void setRoles(String roles) { this.roles = roles; }
    public long getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(long verifiedAt) { this.verifiedAt = verifiedAt; }
    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }
    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }
}
