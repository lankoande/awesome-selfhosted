package io.corebanking.party;

import java.time.LocalDate;
import java.util.UUID;

/** Un tiers, tel qu'il est relu. */
public record Party(
    UUID id,
    UUID legalEntityId,
    String reference,
    PartyKind kind,
    String displayName,
    LocalDate birthOrRegistrationDate,
    String countryCode,
    String segment,
    KycLevel kycLevel,
    KycStatus kycStatus,
    LocalDate kycVerifiedOn,
    LocalDate kycReviewDue,
    RiskRating riskRating,
    PartyStatus status,
    String statusReason) {

    /** Vrai si les comptes du tiers peuvent fonctionner. */
    public boolean operable() {
        return status == PartyStatus.ACTIVE && kycStatus.allowsOperations();
    }

    /** Vrai si un compte ou un credit peut etre ouvert au tiers. */
    public boolean onboardable() {
        return status == PartyStatus.ACTIVE && kycStatus.allowsOnboarding();
    }
}
