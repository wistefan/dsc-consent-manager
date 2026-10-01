package com.seamware.consentmanager.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

/**
 * The two fields of an OpenID Provider Metadata document this service reads.
 *
 * <p>A discovery document (<a
 * href="https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderMetadata">OpenID Connect
 * Discovery 1.0, section 3</a>) carries several dozen members. Only two matter to a resource server
 * that validates signatures and nothing else:
 *
 * <ul>
 *   <li>{@code issuer} - compared byte-for-byte against the configured issuer so that a discovery
 *       URL cannot quietly re-point a trust-list entry at a different provider.
 *   <li>{@code jwks_uri} - where that provider publishes its signing keys.
 * </ul>
 *
 * <p>Everything else is ignored rather than rejected: the document belongs to the provider, which
 * is free to add members, and failing a deserialization over a member this service never reads
 * would turn a provider's unrelated upgrade into an outage here. Micronaut Serde already ignores
 * unknown members by default, but that default is flipped globally by {@code
 * serde.deserialization.ignore-unknown}, so {@link JsonIgnoreProperties} pins it per type: this
 * record's tolerance of extra members is load-bearing and must not depend on a setting made
 * elsewhere for unrelated reasons.
 *
 * <p>The snake-case naming strategy maps {@code jwksUri} onto the specified {@code jwks_uri}
 * member; {@code issuer} is unaffected by it.
 *
 * @param issuer the provider's own view of its issuer identifier, the {@code iss} value it will
 *     mint into tokens
 * @param jwksUri the absolute URL of the provider's JWK Set document
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Serdeable.Deserializable(naming = SnakeCaseStrategy.class)
public record OpenIdProviderMetadata(String issuer, String jwksUri) {}
