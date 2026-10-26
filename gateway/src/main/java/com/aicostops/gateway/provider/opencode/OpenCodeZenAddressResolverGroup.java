package com.aicostops.gateway.provider.opencode;

import com.aicostops.gateway.provider.DispatchEndpointGuard;
import com.aicostops.gateway.provider.PublicOnlyAddressResolverGroup;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Address resolver group for OpenCode Zen: permits strictly public unicast addresses,
 * plus loopback exclusively on the trusted Bun relay port (18992).
 */
public class OpenCodeZenAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {

    private final PublicOnlyAddressResolverGroup.DnsResolver dnsResolver;
    private final int trustedRelayPort;

    public OpenCodeZenAddressResolverGroup() {
        this(PublicOnlyAddressResolverGroup.DnsResolver.system(), OpenCodeZenRelaySupport.DEFAULT_RELAY_PORT);
    }

    public OpenCodeZenAddressResolverGroup(PublicOnlyAddressResolverGroup.DnsResolver dnsResolver) {
        this(dnsResolver, OpenCodeZenRelaySupport.DEFAULT_RELAY_PORT);
    }

    public OpenCodeZenAddressResolverGroup(PublicOnlyAddressResolverGroup.DnsResolver dnsResolver, int trustedRelayPort) {
        this.dnsResolver = dnsResolver;
        this.trustedRelayPort = trustedRelayPort;
    }

    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) throws Exception {
        return new FilteringResolver(executor, dnsResolver, trustedRelayPort);
    }

    static final class FilteringResolver implements AddressResolver<InetSocketAddress> {

        private final EventExecutor executor;
        private final PublicOnlyAddressResolverGroup.DnsResolver dnsResolver;
        private final int trustedRelayPort;

        FilteringResolver(EventExecutor executor, PublicOnlyAddressResolverGroup.DnsResolver dnsResolver, int trustedRelayPort) {
            this.executor = executor;
            this.dnsResolver = dnsResolver;
            this.trustedRelayPort = trustedRelayPort;
        }

        @Override
        public boolean isSupported(java.net.SocketAddress address) {
            return address instanceof InetSocketAddress;
        }

        @Override
        public boolean isResolved(java.net.SocketAddress address) {
            return address instanceof InetSocketAddress isa && isa.getAddress() != null;
        }

        @Override
        public io.netty.util.concurrent.Future<InetSocketAddress> resolve(java.net.SocketAddress address) {
            return resolve(address, executor.newPromise());
        }

        @Override
        public io.netty.util.concurrent.Future<InetSocketAddress> resolve(java.net.SocketAddress address,
                Promise<InetSocketAddress> promise) {
            try {
                var all = resolveAll(address, executor.newPromise()).get();
                promise.setSuccess(all.get(0));
            } catch (Exception ex) {
                promise.setFailure(ex instanceof UnknownHostException uhe ? uhe
                        : new UnknownHostException("Provider DNS resolution failed"));
            }
            return promise;
        }

        @Override
        public io.netty.util.concurrent.Future<List<InetSocketAddress>> resolveAll(java.net.SocketAddress address) {
            return resolveAll(address, executor.newPromise());
        }

        @Override
        public io.netty.util.concurrent.Future<List<InetSocketAddress>> resolveAll(java.net.SocketAddress address,
                Promise<List<InetSocketAddress>> promise) {
            try {
                if (!(address instanceof InetSocketAddress isa)) {
                    throw new UnknownHostException("Unsupported address type");
                }
                var filtered = filter(isa.getHostString(), isa.getPort());
                var out = new ArrayList<InetSocketAddress>(filtered.size());
                for (var ip : filtered) {
                    out.add(new InetSocketAddress(ip, isa.getPort()));
                }
                promise.setSuccess(List.copyOf(out));
            } catch (Exception ex) {
                promise.setFailure(ex instanceof UnknownHostException uhe ? uhe
                        : new UnknownHostException("Provider DNS resolution failed"));
            }
            return promise;
        }

        private List<InetAddress> filter(String host, int port) throws Exception {
            final InetAddress[] resolved;
            try {
                resolved = dnsResolver.resolve(host);
            } catch (UnknownHostException ex) {
                throw new UnknownHostException("Provider DNS resolution failed: " + host);
            }
            if (resolved == null || resolved.length == 0) {
                throw new UnknownHostException("Provider DNS resolution failed: " + host);
            }
            var accepted = new ArrayList<InetAddress>(resolved.length);
            for (var addr : resolved) {
                if (addr == null) {
                    throw new UnknownHostException("Provider target is not valid: " + host);
                }
                // Permit public unicast OR strictly trusted loopback relay on port 18992
                if (DispatchEndpointGuard.isPublicUnicast(addr)
                        || (addr.isLoopbackAddress() && port == trustedRelayPort)) {
                    accepted.add(addr);
                } else {
                    throw new UnknownHostException("Provider target is neither public unicast nor trusted relay: " + host + ":" + port);
                }
            }
            return List.copyOf(accepted);
        }

        @Override
        public void close() {
        }
    }
}
