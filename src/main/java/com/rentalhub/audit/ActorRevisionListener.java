package com.rentalhub.audit;

import org.hibernate.envers.RevisionListener;

/**
 * Stamps each new revision with whoever is acting on this thread. Envers creates this
 * listener itself (it is not a Spring bean), which is why the actor travels in a
 * ThreadLocal rather than being injected.
 */
public class ActorRevisionListener implements RevisionListener {

    @Override
    public void newRevision(Object revisionEntity) {
        ((Revision) revisionEntity).setChangedBy(AuditActor.current());
    }
}
