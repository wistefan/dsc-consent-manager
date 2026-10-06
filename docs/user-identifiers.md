# Identifiers in the user directory

A user in the Consent Manager has exactly one name the service matches on, and
may have any number of names a participant finds convenient. Confusing the two
is the mistake this page exists to prevent.

## The global identifier is the only key

`identifier` is the data space's globally unique user identifier — the same
value the user's own access token asserts in its configured
`claims.user-identifier` claim. Every operation that resolves a user resolves it
on this value, exactly and case-sensitively:

- `POST /participants/me/users` decides create-versus-link on it.
- `GET /users/{identifier}` addresses a user by it.
- `POST /users/search` matches it exactly when the `identifier` criterion is set.

It follows that a participant can only register a user it can already name in
the data space's terms. Obtaining that identifier — from the user's token, from
an onboarding flow, from a dataspace registry — is outside this service.

## `email` is never a key

The registration rule never matches on `email`, and the service never merges two
user records. Two users may share an address, an address may be absent, and a
stored address is whatever claim an identity provider asserted rather than a
verified one. `POST /users/search` can match on `email` as a convenience, which
is why it may answer with more than one user, and why its result must not be
treated as an identity check.

The practical consequence: registering the same human being under two different
global identifiers produces two unrelated users, and the service will not
discover that they are the same person. There is no un-merge because there is no
merge.

## `localIdentifier` is a label, not a key

`localIdentifier` is the calling participant's own identifier for a user — a
medical record number, a customer number, whatever reconciles against the
participant's internal systems. It is stored on *that participant's* link row,
and that is the whole of its contract:

- It is **private to the link.** No other participant can read it, and it is not
  part of any user representation.
- It is **never a lookup key.** No operation resolves a user from it, not even
  for the participant that set it. Addressing is always by global identifier.
- It is **not unique**, not validated, and not interpreted.
- It is the one value a later registration may still change. Supplying it
  refreshes the link; omitting it leaves whatever was there.

If you need to go from your own identifier to a Consent Manager user, keep the
mapping on your side. The alternative — a directory-wide lookup on an opaque,
non-unique, participant-chosen string — would leak one participant's internal
numbering into another's queries, which is precisely what scoping the column to
the link prevents.

## Attributes belong to the record's creator and to the user

`email`, `firstName` and `lastName` are written **only** on the insert that
creates the user. A participant registering a user that already exists adopts the
record as it stands; its attributes are left untouched even when the registration
supplies different ones. Afterwards the user's own identity provider is the only
source allowed to refresh them, through the claims on the user's token.

This is deliberate: a participant should not be able to rewrite what another
participant recorded, still less what the data subject's identity provider
asserts. Send attributes as a best-effort seed for a user nobody has registered
yet, and do not rely on them to correct an existing record.

## Identifiers a path segment cannot carry

Micronaut path variables do not match `/`, and the container decodes percent
escapes before routing, so an identifier containing a slash — a URI- or DID-
shaped one, for instance — is not addressable through `GET /users/{identifier}`
even when encoded. The literal identifier `me` is likewise unreachable, because
`GET /users/me` is the user's own self-service route.

`POST /users/search` with the `identifier` criterion is the documented escape
hatch for both cases. It matches the same value the same way, with no
restriction on its shape.

## Unlinking, and the half of the invariant this ticket cannot enforce

`DELETE /participants/me/users/{identifier}` refuses with `409 Conflict` while a
`GRANTED` consent for that user names the caller as provider or consumer, so
that no granted consent is left naming a participant that no longer knows the
subject.

That check is a `SELECT` followed by a `DELETE`, two statements with no
constraint able to serialise them, so it is advisory rather than a guarantee: a
consent granted in between still slips through. The same hole is open without
any race at all for a consent that is `PENDING` or `DRAFT` at unlink time — it
does not block the unlink, because neither status authorises a data flow, and
granting it afterwards strands it.

The invariant "no `GRANTED` consent without a link" therefore has to be upheld
from the other side as well: **the consent-granting path must refuse to grant a
consent for a participant that holds no link to the subject.** That path does
not exist yet; it belongs to the consent lifecycle ticket, and `UserService.unlink`
carries a `TODO(consent-lifecycle)` pointing here. Only the two halves together
close the gap — widening the unlink check to `PENDING` and `DRAFT` would not,
since it leaves the race untouched while refusing unlinks that nothing justifies.
