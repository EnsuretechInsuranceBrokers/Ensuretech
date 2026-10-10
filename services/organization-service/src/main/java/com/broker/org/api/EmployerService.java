package com.broker.org.api;

import com.broker.org.api.Dtos.*;
import com.broker.org.domain.*;

import com.broker.org.outbox.OutboxPublisher;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** All methods take brokerId explicitly; controllers pass it from TenantContext only. */
@Service
public class EmployerService {
    static final String TOPIC = "employer.events";

    private final EmployerRepository employers;
    private final EmployerConfigRepository configs;
    private final OutboxPublisher outbox;

    EmployerService(EmployerRepository employers, EmployerConfigRepository configs, OutboxPublisher outbox) {
        this.employers = employers;
        this.configs = configs;
        this.outbox = outbox;
    }

    @Transactional
    public Employer create(long brokerId, CreateEmployerRequest r) {
        if (employers.existsByBrokerIdAndEmployerCode(brokerId, r.employerCode())) {
            throw new org.springframework.dao.DataIntegrityViolationException("duplicate employer_code");
        }
        Employer e = new Employer(brokerId, r.employerCode());
        e.setLegalName(r.legalName());
        e.setDisplayName(r.displayName());
        e.setGstin(r.gstin());
        e.setPan(r.pan());
        e.setSector(r.sector());
        e.setEmployeeCount(r.employeeCount());
        e.setContactEmail(r.contactEmail());
        e.setContactPhone(r.contactPhone());
        e.setHrmsType(r.hrmsType());
        e.setHidden(Boolean.TRUE.equals(r.hidden()));
        if (r.parentEmployerId() != null) checkParent(brokerId, null, r.parentEmployerId());
        e.setParentEmployerId(r.parentEmployerId());
        e = employers.saveAndFlush(e);
        emit("EmployerCreated", e);
        return e;
    }

    @Transactional
    public Employer update(long brokerId, long id, UpdateEmployerRequest r) {
        Employer e = get(brokerId, id);
        if (r.legalName() != null) e.setLegalName(r.legalName());
        if (r.displayName() != null) e.setDisplayName(r.displayName());
        if (r.gstin() != null) e.setGstin(r.gstin());
        if (r.pan() != null) e.setPan(r.pan());
        if (r.sector() != null) e.setSector(r.sector());
        if (r.employeeCount() != null) e.setEmployeeCount(r.employeeCount());
        if (r.contactEmail() != null) e.setContactEmail(r.contactEmail());
        if (r.contactPhone() != null) e.setContactPhone(r.contactPhone());
        if (r.hrmsType() != null) e.setHrmsType(r.hrmsType());
        if (r.hidden() != null) e.setHidden(r.hidden());
        e = employers.saveAndFlush(e);
        emit("EmployerUpdated", e);
        return e;
    }

    /** Makes {@code id} a child of {@code parentId}. One level only; same broker; neither side may already be on the other end of a link. */
    @Transactional
    public Employer setParent(long brokerId, long id, long parentId) {
        Employer e = get(brokerId, id);
        checkParent(brokerId, e, parentId);
        e.setParentEmployerId(parentId);
        e = employers.saveAndFlush(e);
        emit("EmployerUpdated", e);
        return e;
    }

    @Transactional
    public Employer clearParent(long brokerId, long id) {
        Employer e = get(brokerId, id);
        if (e.getParentEmployerId() == null) return e;
        e.setParentEmployerId(null);
        e = employers.saveAndFlush(e);
        emit("EmployerUpdated", e);
        return e;
    }

    @Transactional(readOnly = true)
    public List<Employer> children(long brokerId, long id) {
        get(brokerId, id);
        return employers.findByBrokerIdAndParentEmployerIdOrderById(brokerId, id);
    }

    private void checkParent(long brokerId, Employer child, long parentId) {
        if (child != null && child.getId().equals(parentId)) throw new ConflictException("An employer cannot be its own parent");
        Employer parent = get(brokerId, parentId);                                   // same broker, or 404
        if (!parent.isActive()) throw new ConflictException("The parent employer is inactive");
        if (parent.getParentEmployerId() != null) throw new ConflictException("The parent is itself a child company; only one level is supported");
        if (child != null && employers.existsByBrokerIdAndParentEmployerId(brokerId, child.getId())) {
            throw new ConflictException("This employer already has child companies; it cannot become a child");
        }
    }

    @Transactional
    public void deactivate(long brokerId, long id) {
        Employer e = get(brokerId, id);
        if (!e.isActive()) return;
        if (employers.findByBrokerIdAndParentEmployerIdOrderById(brokerId, id).stream().anyMatch(Employer::isActive)) {
            throw new ConflictException("Deactivate or detach the child companies first");
        }
        e.setActive(false);
        emit("EmployerDeactivated", employers.saveAndFlush(e));
    }

    @Transactional(readOnly = true)
    public Employer get(long brokerId, long id) {
        return employers.findByIdAndBrokerId(id, brokerId)
                .orElseThrow(() -> new NotFoundException("Employer not found"));   // 404, never leaks other tenants
    }

    @Transactional(readOnly = true)
    public Page<Employer> list(long brokerId, boolean includeHidden, Pageable page) {
        return includeHidden ? employers.findByBrokerIdAndTestDataFalse(brokerId, page)
                             : employers.findByBrokerIdAndHiddenFalseAndTestDataFalse(brokerId, page);
    }

    @Transactional
    public ConfigResponse putConfig(long brokerId, long employerId, String key, ConfigRequest r) {
        get(brokerId, employerId);
        EmployerConfig c = configs.findByBrokerIdAndEmployerIdAndConfigKey(brokerId, employerId, key)
                .orElseGet(() -> new EmployerConfig(brokerId, employerId, key));
        c.setConfigValue(r.value());
        c.setExpiresAt(r.expiresAt());
        c = configs.saveAndFlush(c);
        outbox.publish("Employer", employerId, "EmployerConfigChanged", TOPIC, String.valueOf(brokerId),
                Map.of("broker_id", brokerId, "employer_id", employerId, "config_key", key));
        return new ConfigResponse(c.getConfigKey(), c.getConfigValue(), c.getExpiresAt());
    }

    @Transactional(readOnly = true)
    public List<ConfigResponse> listConfigs(long brokerId, long employerId) {
        get(brokerId, employerId);
        return configs.findByBrokerIdAndEmployerIdOrderByConfigKey(brokerId, employerId).stream()
                .map(c -> new ConfigResponse(c.getConfigKey(), c.getConfigValue(), c.getExpiresAt())).toList();
    }

    private void emit(String type, Employer e) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("broker_id", e.getBrokerId());
        p.put("employer_id", e.getId());
        p.put("employer_code", e.getEmployerCode());
        p.put("display_name", e.getDisplayName());
        p.put("is_active", e.isActive());
        p.put("parent_employer_id", e.getParentEmployerId());
        outbox.publish("Employer", e.getId(), type, TOPIC, String.valueOf(e.getBrokerId()), p);
    }
}
