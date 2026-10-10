package com.broker.org.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "broker_branches")
public class BrokerBranch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "broker_id", nullable = false, updatable = false) private Long brokerId;
    @Column(name = "branch_code", nullable = false) private String branchCode;
    private String city;
    private String state;
    @Column(name = "is_head_office", nullable = false) private boolean headOffice;
    @Column(name = "is_active", nullable = false) private boolean active = true;
    @Column(name = "is_test_data", nullable = false) private boolean testData = false;

    protected BrokerBranch() {}

    public BrokerBranch(Long brokerId, String branchCode, String city, String state, boolean headOffice) {
        this.brokerId = brokerId;
        this.branchCode = branchCode;
        this.city = city;
        this.state = state;
        this.headOffice = headOffice;
    }

    public Long getId() { return id; }
    public Long getBrokerId() { return brokerId; }
    public String getBranchCode() { return branchCode; }
    public String getCity() { return city; }
    public String getState() { return state; }
    public boolean isHeadOffice() { return headOffice; }
    public boolean isActive() { return active; }
}
