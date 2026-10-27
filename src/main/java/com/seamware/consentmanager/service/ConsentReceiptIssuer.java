package com.seamware.consentmanager.service;

import com.seamware.consentmanager.api.generated.model.ConsentReceiptKantara;
import com.seamware.consentmanager.domain.IssuedReceipt;
import com.seamware.consentmanager.repository.IssuedReceiptRepository;
import com.seamware.consentmanager.signing.ConsentSigningService;
import com.seamware.consentmanager.signing.SignedReceipt;
import io.micronaut.core.annotation.NonNull;
import jakarta.inject.Singleton;
import java.time.Instant;

/**
 * Signs and stores the Kantara receipt of a grant, from inside the transaction that granted it. A
 * signing failure throws and rolls that transaction back: no grant is stored without its receipt.
 */
@Singleton
public class ConsentReceiptIssuer {

    private final ConsentParties parties;
    private final ConsentReceiptService receipts;
    private final ConsentSigningService signing;
    private final IssuedReceiptRepository issued;

    public ConsentReceiptIssuer(
            ConsentParties parties,
            ConsentReceiptService receipts,
            ConsentSigningService signing,
            IssuedReceiptRepository issued) {
        this.parties = parties;
        this.receipts = receipts;
        this.signing = signing;
        this.issued = issued;
    }

    /** Issues the receipt of {@code granted}, dated by the event that just granted it. */
    @NonNull
    public IssuedReceipt issue(@NonNull RecordedConsent granted) {
        Instant grantedAt = granted.event().getOccurredAt();
        SignedReceipt<ConsentReceiptKantara> signed =
                signing.sign(receipts.toKantara(parties.details(granted), grantedAt));
        return issued.save(
                new IssuedReceipt(
                        granted.consent().getId(),
                        granted.event().getId(),
                        signed.canonicalJson(),
                        signed.jws(),
                        signed.keyId(),
                        grantedAt));
    }
}
