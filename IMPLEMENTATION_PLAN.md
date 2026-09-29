# Implementation Plan: Data Models and Schema

## Overview
Implement the PostgreSQL schema (Flyway migration), Java domain entities, enums, JSONB records, and Micronaut Data JDBC repositories for the four core aggregates — User, Participant, PrivacyNotice, and Consent (with ConsentEvent audit log) — plus the `user_participants` link table. All 12 acceptance criteria from the ticket must be satisfied, with repository integration tests running against a real Testcontainers PostgreSQL instance.

## Steps

### Step 1: Flyway migration V1__initial_schema.sql

Create the DDL migration file at `src/main/resources/db/migration/V1__initial_schema.sql` containing:

- `users` table with UUID PK, `identifier` unique constraint, optional `email`/`first_name`/`last_name`, timestamps, and a partial index on `lower(email)`.
- `participants` table with UUID PK, `identifier` unique constraint, `legal_name`, optional `self_description_uri`/`email`, `endpoints` and `legal_person` as JSONB columns, timestamps.
- `user_participants` link table with composite PK `(user_id, participant_id)`, `local_identifier`, `created_at`, and an index on `participant_id`. Both FKs use `ON DELETE CASCADE`.
- `privacy_notices` table with UUID PK, `contract_uri`, `title`, `provider_id`/`consumer_id` FKs to `participants` with `ON DELETE RESTRICT`, `payload` JSONB, `archived_at`, timestamps, and a partial unique index on `(contract_uri, provider_id) WHERE archived_at IS NULL`.
- `consents` table with UUID PK, FKs to `users`, `privacy_notices`, `participants` (provider/consumer) all with `ON DELETE RESTRICT`, self-referencing `parent_consent_id` with `ON DELETE SET NULL`, `status` text with CHECK constraint for the 7 valid states, `consented` boolean, `contract_uri`, `snapshot` JSONB, timestamps, self-reference check `parent_consent_id IS DISTINCT FROM id`, and indexes on `(user_id, status)`, `provider_id`, `consumer_id`, `privacy_notice_id`, plus a partial unique index `(user_id, privacy_notice_id) WHERE status = 'GRANTED'`.
- `consent_events` table with UUID PK, `consent_id` FK with `ON DELETE CASCADE`, `event_state`, `event_type` defaulting to `'explicit'`, `actor`, `occurred_at`, `details` JSONB, and an index on `(consent_id, occurred_at)`.

The DDL must match the schema in the ticket exactly. Use the exact constraint names, index names, and ON DELETE policies specified.

**Files to create:**
- `src/main/resources/db/migration/V1__initial_schema.sql`

**Acceptance criteria covered:** AC-1 (Flyway applies cleanly), AC-3 (unique constraints), AC-4 (partial unique index), AC-5 (CHECK constraint), AC-6 (partial unique index on privacy_notices), AC-7 (cascade/restrict on users), AC-8 (cascade/restrict on participants).

### Step 2: Enums and JSONB record types

Create the Java enums and JSONB-serializable record types that are referenced by the domain entities:

**`ConsentStatus` enum** (`src/main/java/com/seamware/consentmanager/domain/ConsentStatus.java`):
- Values: `PENDING`, `DRAFT`, `GRANTED`, `REVOKED`, `EXPIRED`, `TERMINATED`, `REFUSED`.
- Annotate with `@Serdeable` for Micronaut Serde Jackson serialization.

**`ConsentEventState` enum** (`src/main/java/com/seamware/consentmanager/domain/ConsentEventState.java`):
- Values: `CONSENT_GIVEN`, `CONSENT_REQUESTED`, `CONSENT_REVOKED`, `CONSENT_REFUSED`, `CONSENT_TERMINATED`, `CONSENT_RE_CONFIRMED`, `CONSENT_RESUMED`, `CONSENT_EXPIRED`.
- Annotate with `@Serdeable`.

**`PrivacyNoticePayload` record** (`src/main/java/com/seamware/consentmanager/domain/PrivacyNoticePayload.java`):
- A Java record annotated with `@Serdeable` to enable JSONB round-tripping via Micronaut Serde Jackson.
- Fields representing the contract-derived content: purposes, data categories, recipients, controller details, retention period, PII principal rights, international transfers, withdrawal method.
- Use `List<String>` or similar collection types for multi-valued fields; all fields should be nullable since the payload is contract-derived and may vary.

**`ConsentSnapshot` record** (`src/main/java/com/seamware/consentmanager/domain/ConsentSnapshot.java`):
- A Java record annotated with `@Serdeable` for JSONB serialization.
- Fields representing the frozen copy of privacy notice terms at grant time: purposes, data categories, recipients, retention, jurisdiction.
- All fields nullable; structure mirrors the relevant subset of `PrivacyNoticePayload`.

**Files to create:**
- `src/main/java/com/seamware/consentmanager/domain/ConsentStatus.java`
- `src/main/java/com/seamware/consentmanager/domain/ConsentEventState.java`
- `src/main/java/com/seamware/consentmanager/domain/PrivacyNoticePayload.java`
- `src/main/java/com/seamware/consentmanager/domain/ConsentSnapshot.java`

**Acceptance criteria covered:** AC-9 (ConsentStatus enum used by factory method in Step 3), AC-10 (JSONB round-trip types defined here, tested in Step 5).

### Step 3: Domain entities — User, Participant, UserParticipant

Create the Micronaut Data JDBC entity classes for User, Participant, and the UserParticipant link table.

**`User` entity** (`src/main/java/com/seamware/consentmanager/domain/User.java`):
- Annotated with `@MappedEntity("users")`.
- Fields: `id` (UUID, `@Id`), `identifier` (String), `email` (String, nullable), `firstName` (String, nullable, mapped to `first_name`), `lastName` (String, nullable, mapped to `last_name`), `createdAt` (Instant, mapped to `created_at`), `updatedAt` (Instant, mapped to `updated_at`).
- Use `@MappedProperty` for column name mappings where Java naming differs from SQL.
- Use `@DateCreated` and `@DateUpdated` for automatic timestamp management if supported by Micronaut Data; otherwise handle in constructors/setters.
- Provide a constructor and standard getters/setters.

**`Participant` entity** (`src/main/java/com/seamware/consentmanager/domain/Participant.java`):
- Annotated with `@MappedEntity("participants")`.
- Fields: `id` (UUID, `@Id`), `identifier` (String), `legalName` (String, mapped to `legal_name`), `selfDescriptionUri` (String, nullable, mapped to `self_description_uri`), `email` (String, nullable), `endpoints` (JSONB — use `@TypeDef(type = DataType.JSON)` with a suitable type like `Map<String, Object>` or a typed record), `legalPerson` (JSONB, nullable), `createdAt`, `updatedAt`.

**`UserParticipant` entity** (`src/main/java/com/seamware/consentmanager/domain/UserParticipant.java`):
- Annotated with `@MappedEntity("user_participants")`.
- Composite PK: use `@EmbeddedId` with a `UserParticipantId` embeddable class, or use separate `userId`/`participantId` fields with `@Id` annotation. Research Micronaut Data's composite key support and choose the idiomatic approach.
- Fields: `userId` (UUID, mapped to `user_id`), `participantId` (UUID, mapped to `participant_id`), `localIdentifier` (String, nullable, mapped to `local_identifier`), `createdAt` (Instant, mapped to `created_at`).

**Important notes:**
- All entities must use the package `com.seamware.consentmanager.domain`.
- JSONB fields (`endpoints`, `legalPerson`) require `@TypeDef(type = DataType.JSON)` for Micronaut Data JDBC to handle them correctly.
- Every class and public method must have Javadoc.
- Delete the `.gitkeep` file from `src/main/java/com/seamware/consentmanager/domain/`.

**Files to create:**
- `src/main/java/com/seamware/consentmanager/domain/User.java`
- `src/main/java/com/seamware/consentmanager/domain/Participant.java`
- `src/main/java/com/seamware/consentmanager/domain/UserParticipant.java`

**Files to delete:**
- `src/main/java/com/seamware/consentmanager/domain/.gitkeep`

### Step 4: Domain entities — PrivacyNotice, Consent, ConsentEvent

Create the remaining domain entity classes.

**`PrivacyNotice` entity** (`src/main/java/com/seamware/consentmanager/domain/PrivacyNotice.java`):
- Annotated with `@MappedEntity("privacy_notices")`.
- Fields: `id` (UUID, `@Id`), `contractUri` (String, mapped to `contract_uri`), `title` (String, nullable), `providerId` (UUID, mapped to `provider_id`), `consumerId` (UUID, nullable, mapped to `consumer_id`), `payload` (PrivacyNoticePayload, `@TypeDef(type = DataType.JSON)`), `archivedAt` (Instant, nullable, mapped to `archived_at`), `createdAt`, `updatedAt`.

**`Consent` entity** (`src/main/java/com/seamware/consentmanager/domain/Consent.java`):
- Annotated with `@MappedEntity("consents")`.
- Fields: `id` (UUID, `@Id`), `userId` (UUID, mapped to `user_id`), `privacyNoticeId` (UUID, mapped to `privacy_notice_id`), `providerId` (UUID, mapped to `provider_id`), `consumerId` (UUID, mapped to `consumer_id`), `parentConsentId` (UUID, nullable, mapped to `parent_consent_id`), `status` (ConsentStatus), `consented` (boolean), `contractUri` (String, nullable, mapped to `contract_uri`), `snapshot` (ConsentSnapshot, `@TypeDef(type = DataType.JSON)`), `createdAt`, `updatedAt`.
- **Factory method** (AC-9): Provide a static factory method (e.g., `withStatus(ConsentStatus status, ...)`) or a setter that sets both `status` and `consented` atomically. The `consented` field is `true` only when `status == GRANTED`, `false` otherwise. This is the single place where these two fields are coordinated, preventing divergence (original bug 18.1). Make the direct setter for `status` private or remove it, forcing all callers through the factory method.

**`ConsentEvent` entity** (`src/main/java/com/seamware/consentmanager/domain/ConsentEvent.java`):
- Annotated with `@MappedEntity("consent_events")`.
- Fields: `id` (UUID, `@Id`), `consentId` (UUID, mapped to `consent_id`), `eventState` (ConsentEventState, mapped to `event_state`), `eventType` (String, mapped to `event_type`, default `"explicit"`), `actor` (String, nullable), `occurredAt` (Instant, mapped to `occurred_at`), `details` (JSONB, nullable — `Map<String, Object>` or similar).

**Important notes:**
- The `Consent.withStatus()` factory method must be the single point where `status` and `consented` are set together. Document clearly in Javadoc that this is intentional to prevent bug 18.1.
- JSONB fields use `@TypeDef(type = DataType.JSON)`.

**Files to create:**
- `src/main/java/com/seamware/consentmanager/domain/PrivacyNotice.java`
- `src/main/java/com/seamware/consentmanager/domain/Consent.java`
- `src/main/java/com/seamware/consentmanager/domain/ConsentEvent.java`

**Acceptance criteria covered:** AC-9 (factory method for status/consented).

### Step 5: Repository interfaces

Create Micronaut Data JDBC repository interfaces for all six entity types.

**`UserRepository`** (`src/main/java/com/seamware/consentmanager/repository/UserRepository.java`):
```java
@JdbcRepository(dialect = Dialect.POSTGRES)
public interface UserRepository extends CrudRepository<User, UUID> {
    Optional<User> findByIdentifier(String identifier);
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByIdentifier(String identifier);
}
```

**`ParticipantRepository`** (`src/main/java/com/seamware/consentmanager/repository/ParticipantRepository.java`):
- Extends `CrudRepository<Participant, UUID>` (or `PageableRepository` for pagination).
- Methods: `findByIdentifier`, `existsByIdentifier`, paginated `findAll` returning `Page<Participant>`.

**`UserParticipantRepository`** (`src/main/java/com/seamware/consentmanager/repository/UserParticipantRepository.java`):
- Methods: `findByUserId`, `findByParticipantId`, `existsByUserIdAndParticipantId`, `deleteByUserIdAndParticipantId`.

**`PrivacyNoticeRepository`** (`src/main/java/com/seamware/consentmanager/repository/PrivacyNoticeRepository.java`):
- Extends `CrudRepository<PrivacyNotice, UUID>` or `PageableRepository`.
- Methods: `findByProviderIdAndArchivedAtIsNull` (active notices by provider), `findByContractUriAndProviderIdAndArchivedAtIsNull` (check for active notice before creating a new one), paginated queries.

**`ConsentRepository`** (`src/main/java/com/seamware/consentmanager/repository/ConsentRepository.java`):
- Extends `CrudRepository<Consent, UUID>` or `PageableRepository`.
- Methods: `findByUserId` (paginated), `findByUserIdAndStatus`, `findByProviderId` (paginated), `findByConsumerId` (paginated), `findByPrivacyNoticeId`, `findByParentConsentId`.
- All paginated queries return `Page<Consent>` with filtered counts (AC-12 / original bug 18.3 fix).

**`ConsentEventRepository`** (`src/main/java/com/seamware/consentmanager/repository/ConsentEventRepository.java`):
- Extends `CrudRepository<ConsentEvent, UUID>`.
- Methods: `findByConsentIdOrderByOccurredAtAsc`, `findByConsentId` (paginated).

**Important notes:**
- Use `@JdbcRepository(dialect = Dialect.POSTGRES)` on every repository interface.
- All repository interfaces must be in the `com.seamware.consentmanager.repository` package.
- Paginated methods accept `Pageable` parameter and return `Page<T>`.
- Every method and interface must have Javadoc.
- Delete the `.gitkeep` file from `src/main/java/com/seamware/consentmanager/repository/`.

**Files to create:**
- `src/main/java/com/seamware/consentmanager/repository/UserRepository.java`
- `src/main/java/com/seamware/consentmanager/repository/ParticipantRepository.java`
- `src/main/java/com/seamware/consentmanager/repository/UserParticipantRepository.java`
- `src/main/java/com/seamware/consentmanager/repository/PrivacyNoticeRepository.java`
- `src/main/java/com/seamware/consentmanager/repository/ConsentRepository.java`
- `src/main/java/com/seamware/consentmanager/repository/ConsentEventRepository.java`

**Files to delete:**
- `src/main/java/com/seamware/consentmanager/repository/.gitkeep`

**Acceptance criteria covered:** AC-2 (all six repositories compile and are injectable).

### Step 6: Integration tests for repositories and schema constraints

Create comprehensive integration tests that validate all acceptance criteria against a real Testcontainers PostgreSQL instance. All test classes extend `PostgresTestResource`.

**Test classes to create:**

**`UserRepositoryIT`** (`src/test/java/com/seamware/consentmanager/repository/UserRepositoryIT.java`):
- Test CRUD operations on User.
- Test `findByIdentifier`, `findByEmailIgnoreCase`, `existsByIdentifier`.
- Test AC-3: inserting duplicate `identifier` throws a constraint violation.
- Test AC-7: deleting a user cascades to `user_participants` but is blocked (RESTRICT) when consents exist.

**`ParticipantRepositoryIT`** (`src/test/java/com/seamware/consentmanager/repository/ParticipantRepositoryIT.java`):
- Test CRUD operations on Participant.
- Test AC-3: inserting duplicate `identifier` throws a constraint violation.
- Test AC-8: deleting a participant cascades to `user_participants` but is blocked when consents exist.
- Test JSONB round-trip for `endpoints` and `legalPerson` fields.

**`UserParticipantRepositoryIT`** (`src/test/java/com/seamware/consentmanager/repository/UserParticipantRepositoryIT.java`):
- Test link creation and querying.
- Test cascade deletion when user or participant is deleted.

**`PrivacyNoticeRepositoryIT`** (`src/test/java/com/seamware/consentmanager/repository/PrivacyNoticeRepositoryIT.java`):
- Test CRUD operations.
- Test AC-6: archiving a privacy notice (setting `archived_at`) frees the `(contract_uri, provider_id)` slot for a new active one.
- Test AC-10: `PrivacyNoticePayload` round-trips through JSONB without loss.
- Test the partial unique index rejects duplicate active `(contract_uri, provider_id)`.

**`ConsentRepositoryIT`** (`src/test/java/com/seamware/consentmanager/repository/ConsentRepositoryIT.java`):
- Test CRUD operations.
- Test AC-4: a second GRANTED consent for the same (user, privacy_notice) is rejected by the partial unique index.
- **Test AC-5 (parameterized):** Use `@ParameterizedTest` with `@EnumSource(ConsentStatus.class)` to verify all 7 valid status values are accepted. Use `@ValueSource(strings = {...})` with invalid status strings to verify the CHECK constraint rejects them (this requires raw SQL INSERT since Micronaut Data would use the enum).
- Test AC-9: the factory method sets `consented = true` only for GRANTED, `false` for all others. Use `@ParameterizedTest` over all `ConsentStatus` values.
- Test AC-10: `ConsentSnapshot` round-trips through JSONB without loss.
- Test self-referencing constraint: `parent_consent_id` cannot equal `id`.
- Test ON DELETE RESTRICT: cannot delete user/participant/privacy_notice while consents reference them.

**`ConsentEventRepositoryIT`** (`src/test/java/com/seamware/consentmanager/repository/ConsentEventRepositoryIT.java`):
- Test CRUD operations and ordering by `occurred_at`.
- Test cascade: deleting a consent cascades to its events.

**`ConsentStatusIT`** (`src/test/java/com/seamware/consentmanager/domain/ConsentStatusIT.java`):
- **Parameterized test (AC-12):** Verify every `ConsentStatus` enum value maps to a valid database status string.
- **Parameterized test (AC-12):** Verify invalid status strings (e.g., `"INVALID"`, `"ACTIVE"`, `""`, `null`) are rejected by the CHECK constraint, using raw SQL inserts.

**Important notes:**
- All test classes use `@MicronautTest` and extend `PostgresTestResource`.
- Use `@DisplayName` annotations for human-readable test names.
- Each test method should be focused on one acceptance criterion.
- Use AssertJ for all assertions.
- Clean up test data between tests (use `@BeforeEach` or transactional rollback) to avoid interference. Consider truncation or using unique identifiers per test.
- Parameterized tests must be used where the ticket specifies them (AC-12: every status value, every invalid status value).
- For constraint violation tests, catch the appropriate exception (`DataAccessException` or similar) and assert the expected behavior.

**Files to create:**
- `src/test/java/com/seamware/consentmanager/repository/UserRepositoryIT.java`
- `src/test/java/com/seamware/consentmanager/repository/ParticipantRepositoryIT.java`
- `src/test/java/com/seamware/consentmanager/repository/UserParticipantRepositoryIT.java`
- `src/test/java/com/seamware/consentmanager/repository/PrivacyNoticeRepositoryIT.java`
- `src/test/java/com/seamware/consentmanager/repository/ConsentRepositoryIT.java`
- `src/test/java/com/seamware/consentmanager/repository/ConsentEventRepositoryIT.java`
- `src/test/java/com/seamware/consentmanager/domain/ConsentStatusIT.java`

**Acceptance criteria covered:** AC-1 through AC-12 (all).
