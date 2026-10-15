package com.seamware.consentmanager.service;

import com.seamware.consentmanager.error.BadRequestException;
import java.util.Arrays;
import java.util.stream.Collectors;

/** The side of a contract a participant takes: the one publishing notices, or their recipient. */
public enum ContractSide {
    /** The participant publishes privacy notices under the contract. */
    PROVIDER("provider"),
    /** The participant is named as the recipient of the contract's notices. */
    CONSUMER("consumer");

    private final String value;

    ContractSide(String value) {
        this.value = value;
    }

    /** The side as the API spells it. */
    public String value() {
        return value;
    }

    /** Parses the API spelling; {@code 400} for anything else, including a different case. */
    public static ContractSide fromValue(String value) {
        return Arrays.stream(values())
                .filter(side -> side.value.equals(value))
                .findFirst()
                .orElseThrow(
                        () ->
                                new BadRequestException(
                                        "Unknown contract role '"
                                                + value
                                                + "'; expected one of "
                                                + Arrays.stream(values())
                                                        .map(ContractSide::value)
                                                        .collect(Collectors.joining(", "))
                                                + "."));
    }
}
