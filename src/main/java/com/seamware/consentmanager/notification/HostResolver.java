package com.seamware.consentmanager.notification;

import io.micronaut.context.annotation.DefaultImplementation;
import io.micronaut.core.annotation.NonNull;
import jakarta.inject.Singleton;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** Name resolution for webhook hosts; a seam so tests can stall or fail it. May block. */
@DefaultImplementation(HostResolver.SystemHostResolver.class)
public interface HostResolver {

    /** Every address the host resolves to; throws on any lookup failure, transient or not. */
    @NonNull
    InetAddress[] resolve(@NonNull String host) throws UnknownHostException;

    /** The JDK resolver, which has no timeout of its own. */
    @Singleton
    final class SystemHostResolver implements HostResolver {
        @Override
        @NonNull
        public InetAddress[] resolve(@NonNull String host) throws UnknownHostException {
            return InetAddress.getAllByName(host);
        }
    }
}
