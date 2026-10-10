package com.pulseguard.notification.security;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Seam over DNS so tests can simulate resolution (including rebinding attempts). */
@FunctionalInterface
public interface HostResolver {

    InetAddress[] resolve(String host) throws UnknownHostException;

    /** System DNS. */
    HostResolver SYSTEM = InetAddress::getAllByName;
}
