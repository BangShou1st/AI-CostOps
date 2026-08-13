package com.aicostops.iam.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aicostops.iam.application.LoginCommand;
import com.aicostops.iam.application.LoginResult;
import com.aicostops.iam.application.LoginService;
import com.aicostops.iam.application.PasswordResetService;
import com.aicostops.iam.infrastructure.IssuedAccessToken;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:mysql://127.0.0.1:1/forwarded_test",
        "spring.datasource.username=test",
        "spring.datasource.password=test"
})
class ForwardedClientAddressIntegrationTest {
    @LocalServerPort int port;
    @Autowired WebServerApplicationContext applicationContext;
    @Autowired ServerProperties serverProperties;
    @MockitoBean LoginService loginService;
    @MockitoBean PasswordResetService passwordResetService;

    @Test
    void embeddedServerSuppliesTrustedForwardedAddressToLoginRateLimitInput() throws Exception {
        var tomcat = (TomcatWebServer) applicationContext.getWebServer();
        assertThat(serverProperties.getForwardHeadersStrategy())
                .isEqualTo(ServerProperties.ForwardHeadersStrategy.NATIVE);
        assertThat(tomcat.getTomcat().getEngine().getPipeline().getValves())
                .anyMatch(RemoteIpValve.class::isInstance);
        when(loginService.login(any())).thenReturn(new LoginResult(new IssuedAccessToken("access", 900),
                "session.secret", 1, "User", 2, 3));
        postLogin("198.51.100.10");
        postLogin("198.51.100.11");
        postLogin("198.51.100.10");

        var command = ArgumentCaptor.forClass(LoginCommand.class);
        verify(loginService, times(3)).login(command.capture());
        assertThat(command.getAllValues()).extracting(LoginCommand::remoteIp)
                .containsExactly("198.51.100.10", "198.51.100.11", "198.51.100.10");

        postForgot("203.0.113.20");
        verify(passwordResetService).forgot("forwarded@auth.test", "203.0.113.20");
    }

    private void postLogin(String forwardedAddress) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", forwardedAddress)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"forwarded@auth.test\",\"password\":\"password\"}"))
                .build();
        assertThat(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(200);
    }

    private void postForgot(String forwardedAddress) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/v1/auth/password/forgot"))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", forwardedAddress)
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"forwarded@auth.test\"}"))
                .build();
        assertThat(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(202);
    }
}
