package com.pulseguard.notification.service;

import com.pulseguard.incident.model.Incident;

/**
 * Records incident lifecycle events in the transactional outbox. Implementations must run inside the
 * caller's transaction (so the event commits or rolls back together with the incident change) and must
 * never perform network I/O.
 */
public interface NotificationEnqueuer {

    void incidentOpened(Incident incident);

    /** Called for every recorded occurrence; the anti-spam policy decides whether an event is written. */
    void incidentOccurrence(Incident incident);

    void incidentResolved(Incident incident);
}
