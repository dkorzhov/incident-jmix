package com.company.incidentjmix.entity;

import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Technical counter row backing the gap-free human-readable incident id
 * sequence INC-NNN (ADR-001). Accessed ONLY through EntityManager with a
 * pessimistic write lock inside IncidentService.create; no VERSION column
 * by ADR contract. The single row ('incident', 1) is inserted by
 * 050-id-counter.xml.
 */
@JmixEntity
@Entity(name = "inc_IncidentCounter")
@Table(name = "INC_ID_COUNTER")
public class IncidentCounter {

    @Column(name = "NAME", nullable = false, length = 64)
    @Id
    private String id;

    @Column(name = "NEXT_VALUE", nullable = false)
    private Long nextValue;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getNextValue() {
        return nextValue;
    }

    public void setNextValue(Long nextValue) {
        this.nextValue = nextValue;
    }
}
