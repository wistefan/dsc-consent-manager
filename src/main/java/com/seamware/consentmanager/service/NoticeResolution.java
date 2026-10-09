package com.seamware.consentmanager.service;

import io.micronaut.core.annotation.NonNull;

/**
 * How far each section of a notice could be filled in from documents this service does not own.
 *
 * <p>Four sections rather than one verdict, because a retrieval serves the notice whatever
 * happened: a caller has to be able to tell which heading was read live and which is the stored
 * text. {@code contract} and {@code purposes} move together by construction - the purposes are read
 * off the contract - and that is reported rather than hidden.
 */
public record NoticeResolution(
        @NonNull State contract,
        @NonNull State dataResources,
        @NonNull State purposes,
        @NonNull State recipients) {

    /** How far one section got. */
    public enum State {
        /** Every external document the section needs was read in full. */
        RESOLVED,
        /** Resolution was asked for and at least one document could not be read. */
        UNAVAILABLE,
        /** Resolution was not asked for, so nothing external was read at all. */
        NOT_REQUESTED
    }

    /** What a retrieval that dereferenced nothing reports. */
    @NonNull
    public static NoticeResolution notRequested() {
        return new NoticeResolution(
                State.NOT_REQUESTED, State.NOT_REQUESTED, State.NOT_REQUESTED, State.NOT_REQUESTED);
    }

    /** {@code RESOLVED} when everything the section needed was read, {@code UNAVAILABLE} if not. */
    @NonNull
    public static State stateOf(boolean complete) {
        return complete ? State.RESOLVED : State.UNAVAILABLE;
    }
}
