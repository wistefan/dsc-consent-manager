package com.seamware.consentmanager.service;

import io.micronaut.core.annotation.NonNull;

/**
 * How far each section of a notice could be filled in from documents this service does not own.
 * {@code contract} and {@code purposes} move together: purposes are read off the contract.
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
