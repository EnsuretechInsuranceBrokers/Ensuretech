package com.broker.org.api;

import com.broker.org.api.Dtos.*;
import com.broker.org.domain.Broker;
import com.broker.org.domain.BrokerRepository;
import com.broker.org.outbox.OutboxPublisher;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Platform-level (SYSTEM role) broker onboarding. Gateway rule in SecurityConfig restricts to ROLE_SYSTEM. */
@RestController
@RequestMapping("/api/v1/admin/brokers")
public class AdminBrokerController {
    private final BrokerRepository brokers;
    private final OutboxPublisher outbox;

    AdminBrokerController(BrokerRepository brokers, OutboxPublisher outbox) {
        this.brokers = brokers;
        this.outbox = outbox;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    BrokerResponse create(@Valid @RequestBody CreateBrokerRequest r) {
        Broker b = new Broker();
        b.setCode(r.code());
        b.setName(r.name());
        b.setLegalName(r.legalName());
        b.setGstin(r.gstin());
        b.setPan(r.pan());
        b.setIrdaiLicenceNo(r.irdaiLicenceNo());
        b.setContactEmail(r.contactEmail());
        b.setContactPhone(r.contactPhone());
        b = brokers.saveAndFlush(b);
        outbox.publish("Broker", b.getId(), "BrokerCreated", "employer.events", String.valueOf(b.getId()),
                Map.of("broker_id", b.getId(), "code", b.getCode(), "name", b.getName()));
        return BrokerResponse.of(b);
    }

    @GetMapping
    List<BrokerResponse> list() {
        return brokers.findAll().stream().map(BrokerResponse::of).toList();
    }
}
