package greetings;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@Profile({"default", "insecure"})
@RestController
@RequestMapping("/api")
class RestTemplateGreetingsClientApiGateway {

    private final RestTemplate restTemplate;

    @Autowired
    RestTemplateGreetingsClientApiGateway(@LoadBalanced RestTemplate restTemplate) { // <1>
        this.restTemplate = restTemplate;
    }

    @GetMapping("/resttemplate/{name}")
    Map<String, String> restTemplate(@PathVariable String name) {

        //@formatter:off
        ParameterizedTypeReference<Map<String, String>> type =
                new ParameterizedTypeReference<Map<String, String>>() {
                };
        //@formatter:on

        ResponseEntity<Map<String, String>> responseEntity
                = restTemplate.exchange(
                        "http://greetings-service/greet/{name}", HttpMethod.GET, null, type, name);
        return responseEntity.getBody();
    }
}
