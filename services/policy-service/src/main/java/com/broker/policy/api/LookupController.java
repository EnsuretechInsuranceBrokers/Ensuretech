package com.broker.policy.api;

import com.broker.policy.domain.*;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reference data for policy-configuration UIs. Not tenant data; broker roles only (gateway rule). */
@RestController
@RequestMapping("/api/v1/broker/lookups")
public class LookupController {
    public record Item(Short id, String code, String name) {}

    public record PremiumTypeItem(Short id, String code, String name, String raterClass, List<String> constructRaters) {}

    private final PremiumTypeRepository premiumTypes;
    private final SumInsuredSubtypeRepository sumInsured;
    private final RaterTypeRepository raterTypes;
    private final RelationTypeRepository relations;

    LookupController(PremiumTypeRepository premiumTypes, SumInsuredSubtypeRepository sumInsured,
                     RaterTypeRepository raterTypes, RelationTypeRepository relations) {
        this.premiumTypes = premiumTypes;
        this.sumInsured = sumInsured;
        this.raterTypes = raterTypes;
        this.relations = relations;
    }

    @GetMapping("/premium-types")
    List<PremiumTypeItem> premiumTypes() {
        return premiumTypes.findAll().stream().filter(PremiumType::isActive)
                .map(p -> new PremiumTypeItem(p.getId(), p.getCode(), p.getName(), p.getRaterClass(), p.getConstructRaters()))
                .sorted(java.util.Comparator.comparing(PremiumTypeItem::id)).toList();
    }

    @GetMapping("/suminsured-subtypes")
    List<Item> sumInsured() {
        return sumInsured.findAll().stream().map(s -> new Item(s.getId(), s.getCode(), s.getName()))
                .sorted(java.util.Comparator.comparing(Item::id)).toList();
    }

    @GetMapping("/rater-types")
    List<Item> raterTypes() {
        return raterTypes.findAll().stream().map(s -> new Item(s.getId(), s.getCode(), s.getName()))
                .sorted(java.util.Comparator.comparing(Item::id)).toList();
    }

    @GetMapping("/relation-types")
    List<Item> relations() {
        return relations.findAll().stream().map(s -> new Item(s.getId(), s.getCode(), s.getName()))
                .sorted(java.util.Comparator.comparing(Item::id)).toList();
    }
}
