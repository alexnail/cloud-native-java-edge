package auth.clients;

import java.util.Collections;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.provider.ClientDetailsService;
import org.springframework.security.oauth2.provider.ClientRegistrationException;
import org.springframework.security.oauth2.provider.client.BaseClientDetails;

@Configuration
public class ClientConfiguration {

    private final LoadBalancerClient loadBalancerClient;

    @Autowired
    public ClientConfiguration(LoadBalancerClient client) {
        this.loadBalancerClient = client;
    }

    @Bean
    ClientDetailsService clientDetailsService(ClientRepository clientRepository) {
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        return clientId -> clientRepository
                .findByClientId(clientId)
                .map(client -> {
                    BaseClientDetails details = new BaseClientDetails(client.getClientId(), null,
                            client.getScopes(), client.getAuthorizedGrantTypes(), client.getAuthorities());
                    details.setClientSecret(encoder.encode(client.getSecret()));
                    // <1>
                    // details.setAutoApproveScopes
                    //    (Arrays.asList(client.getAutoApproveScopes().split(",")));

                    // <2>
                    String greetingsClientRedirectUri = Optional
                            .ofNullable(loadBalancerClient.choose("greetings-client"))
                            .map(si -> "http://" + si.getHost() + ':' + si.getPort() + '/')
                            .orElseThrow(() -> new ClientRegistrationException(
                                    "couldn't find and bind a greetings-client IP"));

                    details.setRegisteredRedirectUri(Collections.singleton(greetingsClientRedirectUri));
                    return details;
                })
                .orElseThrow(() -> new ClientRegistrationException(String.format("no client %s registered", clientId)));
    }
}
