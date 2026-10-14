package com.seamware.consentmanager.service;

import java.util.List;

/**
 * Everything one resume wrote: the request it answered, and the rows it closed in order to answer
 * it.
 *
 * <p>The collateral moves — the grant the resume supersedes, and every open request it leaves
 * redundant — are carried out rather than discarded because each is a status change in its own
 * right. A caller reporting only the addressed row would leave a superseded grant looking as though
 * it still stood.
 */
public record ResumedConsent(ConsentMove answered, List<ConsentMove> collateral) {}
