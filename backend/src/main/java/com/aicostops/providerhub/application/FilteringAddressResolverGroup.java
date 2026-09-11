package com.aicostops.providerhub.application;

import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Transport-level DNS policy for the control plane (M18 round-3).
 *
 * <p>Resolution, policy filtering and TCP connect happen in one transport boundary: Netty
 * resolves through this group exactly once per connection and connects only to the returned
 * addresses, so a DNS flip between validation time and connect time cannot smuggle a
 * non-public destination into the actual socket. Hostname is preserved for Host/TLS SNI and
 * certificate hostname verification (never disabled, never trust-all). Literal IPs bypass DNS
 * resolution by construction; they are policed by URL validation instead (no TOCTOU without DNS).
 * Production always uses system DNS plus the strict public-unicast policy; lenient combinations
 * exist only in test sourcesets for controlled loopback servers.
 */
final class FilteringAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {

    private final CustomEndpointValidator.DnsResolver dnsResolver;
    private final Predicate<InetAddress> addressPolicy;

    FilteringAddressResolverGroup(CustomEndpointValidator.DnsResolver dnsResolver,
            Predicate<InetAddress> addressPolicy) {
        this.dnsResolver = dnsResolver;
        this.addressPolicy = addressPolicy;
    }

    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) throws Exception {
        return new FilteringResolver(executor, dnsResolver, addressPolicy);
    }

    static final class FilteringResolver implements AddressResolver<InetSocketAddress> {

        private final EventExecutor executor;
        private final CustomEndpointValidator.DnsResolver dnsResolver;
        private final Predicate<InetAddress> addressPolicy;

        FilteringResolver(EventExecutor executor, CustomEndpointValidator.DnsResolver dnsResolver,
                Predicate<InetAddress> addressPolicy) {
            this.executor = executor;
            this.dnsResolver = dnsResolver;
            this.addressPolicy = addressPolicy;
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
            resolveAll(address, executor.newPromise()).addListener(future -> {
                if (!future.isSuccess()) {
                    promise.setFailure(future.cause());
                    return;
                }
                @SuppressWarnings("unchecked")
                var all = (List<InetSocketAddress>) future.getNow();
                if (all.isEmpty()) {
                    promise.setFailure(new UnknownHostException("Provider DNS resolution failed"));
                    return;
                }
                promise.setSuccess(all.get(0));
            });
            return promise;
        }

        @Override
        public io.netty.util.concurrent.Future<List<InetSocketAddress>> resolveAll(
                java.net.SocketAddress address) {
            return resolveAll(address, executor.newPromise());
        }

        @Override
        public io.netty.util.concurrent.Future<List<InetSocketAddress>> resolveAll(
                java.net.SocketAddress address, Promise<List<InetSocketAddress>> promise) {
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
                if (addr == null || !addressPolicy.test(addr)) {
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
}
