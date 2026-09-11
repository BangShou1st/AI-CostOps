package com.aicostops.gateway.provider;

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
 * Transport-level DNS rebinding defense (M18 repair).
 *
 * <p>Every resolve filters to public unicast only; private/loopback/link-local/multicast/ULA
 * targets fail resolution so the actual socket never connects to a non-public address even when
 * activation-time validation saw a public address (DNS flip between validation and connect).
 * Hostname is preserved for Host/TLS SNI/certificate verification; only the resolved address set
 * is constrained. No bypass flag exists. Tests inject a fake {@link DnsResolver}.
 */
public class PublicOnlyAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {

    private final DnsResolver dnsResolver;

    public PublicOnlyAddressResolverGroup() {
        this(DnsResolver.system());
    }

    PublicOnlyAddressResolverGroup(DnsResolver dnsResolver) {
        this.dnsResolver = dnsResolver;
    }

    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) throws Exception {
        return new FilteringResolver(executor, dnsResolver);
    }

    static final class FilteringResolver implements AddressResolver<InetSocketAddress> {

        private final EventExecutor executor;
        private final DnsResolver dnsResolver;

        FilteringResolver(EventExecutor executor, DnsResolver dnsResolver) {
            this.executor = executor;
            this.dnsResolver = dnsResolver;
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
                var filtered = filter(isa.getHostString());
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

        private List<InetAddress> filter(String host) throws Exception {
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
                if (addr == null || !DispatchEndpointGuard.isPublicUnicast(addr)) {
                    throw new UnknownHostException("Provider target is not public: " + host);
                }
                accepted.add(addr);
            }
            return List.copyOf(accepted);
        }

        @Override
        public void close() {
        }
    }

    /** Test seam: DNS resolution strategy (production uses the JVM resolver). */
    public interface DnsResolver {
        InetAddress[] resolve(String host) throws Exception;

        static DnsResolver system() {
            return InetAddress::getAllByName;
        }
    }
}
