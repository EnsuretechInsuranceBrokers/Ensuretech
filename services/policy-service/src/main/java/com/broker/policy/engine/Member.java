package com.broker.policy.engine;

import java.math.BigDecimal;
import java.util.List;

/** One person to rate. Dependents without a grade/designation inherit the employee's (SELF) values. */
public record Member(String ref, String relationCode, int age, String grade, String designation,
                     BigDecimal sumInsured, List<String> layers) {
    public Member {
        layers = layers == null ? List.of() : List.copyOf(layers);
    }
}
