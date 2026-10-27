package com.seamware.consentmanager.service;

import java.util.List;

/**
 * Everything one resume wrote: the request it answered, and the rows it closed to answer it.
 * Collateral (superseded grant, redundant requests) must be reported too, as status changes.
 */
public record ResumedConsent(ConsentMove answered, List<ConsentMove> collateral) {}
