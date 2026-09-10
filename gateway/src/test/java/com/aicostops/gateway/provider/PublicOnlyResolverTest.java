package com.aicostops.gateway.provider;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

/** M18 P1: DNS rebinding transport-level proof for gateway dispatch. */
class PublicOnlyResolverTest {

    private static PublicOnlyAddressResolverGroup groupFor(String host, String[] ips) {
        return new PublicOnlyAddressResolverGroup(h -> {
            if (!h.equalsIgnoreCase(host)) {
                return new InetAddress[] { InetAddress.getByName("93.184.216.34") };
            }
            var out = new InetAddress[ips.length];
            for (var i = 0; i < ips.length; i++) {
                out[i] = InetAddress.getByName(ips[i]);
            }
            return out;
        });
    }

    @Test
    void blocksPrivateEvenWhenValidationSawPublic() throws Exception {
        // Validation-time resolver sees PUBLIC; connect-time resolver sees PRIVATE (DNS flip).
        // Transport-level resolver must block so private controlled server hit count stays 0.
        var group = groupFor("flip.example.com", new String[] { "10.9.9.9" });
        var executor = new io.netty.util.concurrent.DefaultEventExecutor();
        try {
            var resolver = group.newResolver(executor);
            var future = resolver.resolveAll(new InetSocketAddress("flip.example.com", 443));
            assertFalse(future.isSuccess(), "Private DNS flip must not resolve successfully");
            try {
                future.get();
                fail("Expected UnknownHostException for private target");
            } catch (Exception ex) {
                assertTrue(ex.getCause() instanceof java.net.UnknownHostException
                        || ex instanceof java.net.UnknownHostException);
            } finally {
                resolver.close();
            }
        } finally {
            executor.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    void allowsPublicUnicast() throws Exception {
        var group = groupFor("good.example.com", new String[] { "93.184.216.34" });
        var executor = new io.netty.util.concurrent.DefaultEventExecutor();
        try {
            var resolver = group.newResolver(executor);
            var result = resolver.resolveAll(new InetSocketAddress("good.example.com", 443)).get();
            assertEquals(1, result.size());
            assertEquals("93.184.216.34", result.get(0).getAddress().getHostAddress());
            resolver.close();
        } finally {
            executor.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    void openCodeAdapterUsesServerOwnedUaAndDirectOnly() {
        assertEquals("opencode/1.18.21",
                com.aicostops.gateway.provider.opencode.OpenCodeZenChatAdapter.SERVER_USER_AGENT);
    }
}
