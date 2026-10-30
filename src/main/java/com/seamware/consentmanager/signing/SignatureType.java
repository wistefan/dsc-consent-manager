package com.seamware.consentmanager.signing;

import com.nimbusds.jose.JOSEObjectType;

/**
 * What a signature covers, carried as the JWS {@code typ} and required on verification, so a
 * signature over one kind of document never verifies as another under the shared key.
 */
public enum SignatureType {
    /** A consent receipt; {@code JOSE}, as every receipt has been signed. */
    RECEIPT(JOSEObjectType.JOSE),

    /** A participant notification sent to a webhook. */
    NOTIFICATION(new JOSEObjectType("consent-notification+jws"));

    private final JOSEObjectType header;

    SignatureType(JOSEObjectType header) {
        this.header = header;
    }

    /** The {@code typ} header value. */
    public JOSEObjectType header() {
        return header;
    }
}
