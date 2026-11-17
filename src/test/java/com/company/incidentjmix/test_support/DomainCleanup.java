package com.company.incidentjmix.test_support;

import com.company.incidentjmix.entity.Comment;
import com.company.incidentjmix.entity.HistoryEvent;
import com.company.incidentjmix.entity.Incident;
import com.company.incidentjmix.entity.IncidentCounter;
import io.jmix.core.DataManager;
import io.jmix.core.Id;

import java.util.ArrayList;
import java.util.List;

/**
 * Cleanup helper for domain tests against the shared file-based HSQLDB.
 * Tracks created incidents, removes them in FK-safe order (history, comments,
 * incident) by reloading each row before removal, and restores the INC-NNN
 * counter baseline so id assertions stay relative across runs.
 *
 * The caller is responsible for authentication (test extension or
 * SystemAuthenticator) around every method call.
 */
public class DomainCleanup {

    private final DataManager dataManager;
    private final long counterBaseline;
    private final List<String> incidentIds = new ArrayList<>();

    public DomainCleanup(DataManager dataManager) {
        this.dataManager = dataManager;
        this.counterBaseline = counterValue();
    }

    public long counterValue() {
        return dataManager.load(IncidentCounter.class).id("incident").one().getNextValue();
    }

    public long counterBaseline() {
        return counterBaseline;
    }

    public void track(String incidentId) {
        incidentIds.add(incidentId);
    }

    public void track(Incident incident) {
        incidentIds.add(incident.getId());
    }

    /**
     * Removes every incident in the store together with its children and
     * restores the counter baseline, so a following create() reuses the same
     * INC-NNN ids as before the purge (review finding on PR #3).
     */
    public void purgeAllIncidents() {
        List<Incident> all = dataManager.load(Incident.class)
                .query("select e from inc_Incident e")
                .list();
        for (Incident incident : all) {
            removeIncidentWithChildren(incident.getId());
        }
        restoreCounterBaseline();
    }

    /** Removes tracked incidents and restores the counter baseline. */
    public void cleanup() {
        for (String id : incidentIds) {
            removeIncidentWithChildren(id);
        }
        incidentIds.clear();
        restoreCounterBaseline();
    }

    private void restoreCounterBaseline() {
        IncidentCounter counter = dataManager.load(IncidentCounter.class).id("incident").one();
        if (counter.getNextValue() != counterBaseline) {
            counter.setNextValue(counterBaseline);
            dataManager.saveWithoutReload(counter);
        }
    }

    private void removeIncidentWithChildren(String incidentId) {
        List<HistoryEvent> history = dataManager.load(HistoryEvent.class)
                .query("select e from inc_HistoryEvent e where e.incident.id = :incidentId")
                .parameter("incidentId", incidentId)
                .list();
        if (!history.isEmpty()) {
            dataManager.remove(history.toArray());
        }
        List<Comment> comments = dataManager.load(Comment.class)
                .query("select e from inc_Comment e where e.incident.id = :incidentId")
                .parameter("incidentId", incidentId)
                .list();
        if (!comments.isEmpty()) {
            dataManager.remove(comments.toArray());
        }
        dataManager.load(Incident.class).id(incidentId).optional()
                .ifPresent(incident -> dataManager.remove(Id.of(incident)));
    }
}
