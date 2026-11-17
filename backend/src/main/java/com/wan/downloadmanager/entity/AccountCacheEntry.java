package com.wan.downloadmanager.entity;

import jakarta.persistence.*;

/** Display cache of WAN account claims (never tokens). */
@Entity
@Table(name = "account_cache")
public class AccountCacheEntry {

    @Id
    @Column(name = "sub")
    private String sub;

    @Column(name = "name")
    private String name;
    @Column(name = "preferred_username")
    private String preferredUsername;
    @Column(name = "email")
    private String email;
    @Column(name = "email_verified")
    private Boolean emailVerified;
    @Column(name = "phone")
    private String phone;
    @Column(name = "phone_verified")
    private Boolean phoneVerified;
    @Column(name = "locale")
    private String locale;
    @Column(name = "issuer")
    private String issuer;
    @Column(name = "verified_at")
    private long verifiedAt;

    public String getSub() { return sub; }
    public void setSub(String sub) { this.sub = sub; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPreferredUsername() { return preferredUsername; }
    public void setPreferredUsername(String preferredUsername) { this.preferredUsername = preferredUsername; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public Boolean getEmailVerified() { return emailVerified; }
    public void setEmailVerified(Boolean emailVerified) { this.emailVerified = emailVerified; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public Boolean getPhoneVerified() { return phoneVerified; }
    public void setPhoneVerified(Boolean phoneVerified) { this.phoneVerified = phoneVerified; }
    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public long getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(long verifiedAt) { this.verifiedAt = verifiedAt; }
}
