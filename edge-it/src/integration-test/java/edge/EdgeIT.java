package edge;

import static org.springframework.http.HttpHeaders.*;
import static org.springframework.http.MediaType.parseMediaType;

import cnj.CloudFoundryService;
import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.applications.RestartApplicationRequest;
import org.cloudfoundry.operations.applications.SetEnvironmentVariableApplicationRequest;
import org.cloudfoundry.operations.applications.UnsetEnvironmentVariableApplicationRequest;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.Base64Utils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = Config.class)
public class EdgeIT {

    private static final boolean HTTPS = true;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private RetryTemplate retryTemplate;

    @Autowired
    private CloudFoundryOperations cloudFoundryOperations;

    @Autowired
    private CloudFoundryService service;

    private File root, authServiceManifest, eurekaManifest, edgeServiceManifest,
            greetingsServiceManifest, html5ClientManifest;

    // never deletes the apps if they're already there
    private static volatile boolean RESET = false;

    private final Log log = LogFactory.getLog(getClass());

    @Before
    public void before() throws Throwable {
        log.info("RESET=" + RESET);
        baseline(RESET);
        RESET = false;
    }

    @Test
    // this should work.. not 100% after the version revs.
    public void restClients() throws Throwable {

        log.info("running restClients()");
        // resttemplate
        baselineDeploy(new String[]{"insecure"},
                Collections.singletonMap("security.basic.enabled", "false"), null,
                new String[]{"insecure"},
                Collections.singletonMap("security.basic.enabled", "false"), null);
        testEdgeRestClient("Shafer", "/api/resttemplate/");

        // feign
        baselineDeploy(new String[]{"insecure"},
                Collections.singletonMap("security.basic.enabled", "false"), null,
                "insecure,feign".split(","),
                Collections.singletonMap("security.basic.enabled", "false"), null);
        testEdgeRestClient("Watters", "/api/feign/");
    }

    @Test
    public void testAuth() throws Throwable {

        log.info("running testAuth()");

        ApplicationInstanceConfiguration callback = (appId) -> {
            String prop = "security.basic.enabled";
            cloudFoundryOperations
                    .applications()
                    .unsetEnvironmentVariable(
                            UnsetEnvironmentVariableApplicationRequest.builder().name(appId)
                                    .variableName(prop).build()).block();
        };

        baselineDeploy(new String[]{"secure"}, new HashMap<>(), callback,
                new String[]{"secure", "sso"}, new HashMap<>(), callback);

        String accessToken = obtainToken();

        String userEndpointOnEdgeService = service.urlForApplication(
                appNameFromManifest(greetingsServiceManifest), HTTPS) + "/greet/OAuth";

        RequestEntity<Void> requestEntity = RequestEntity
                .get(URI.create(userEndpointOnEdgeService))
                .header(HttpHeaders.AUTHORIZATION, "bearer " + accessToken).build();
        ResponseEntity<String> responseEntity = restTemplate.exchange(requestEntity,
                String.class);
        String body = responseEntity.getBody();
        log.info("body from authorized request: " + body);
    }

    @Test
    public void testCors() throws Throwable {
        log.info("running testCors()");
        Map<String, String> e = Collections.singletonMap("security.basic.enabled", "false");
        baselineDeploy(new String[]{"insecure"}, e, null, "cors,insecure".split(","), e, null);
        String edgeServiceUri =
                service.urlForApplication(appNameFromManifest(edgeServiceManifest), HTTPS) + "/lets/greet/Phil";
        String html5ClientUri = service.urlForApplication(appNameFromManifest(html5ClientManifest), HTTPS) + "";
        log.info("edge-service URI " + edgeServiceUri);
        log.info("html5-client URI " + html5ClientUri);
        List<String> headerList = Arrays.asList(ACCEPT, "X-Requested-With", ORIGIN);
        String headersString = StringUtils.arrayToDelimitedString(
                headerList.toArray(), ", ").trim();
        RequestEntity<Void> requestEntity = RequestEntity
                .options(URI.create(edgeServiceUri))
                .header(ACCEPT, parseMediaType("*/*").toString())
                .header(ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.toString())
                .header(ACCESS_CONTROL_REQUEST_HEADERS, headersString)
                .header(REFERER, html5ClientUri).header(ORIGIN, html5ClientUri).build();
        Set<HttpMethod> httpMethods = restTemplate.optionsForAllow(edgeServiceUri);
        httpMethods.forEach(m -> log.info(m));
        ResponseEntity<Void> responseEntity = retryTemplate.execute(ctx -> {
            ResponseEntity<Void> exchange = restTemplate.exchange(requestEntity,
                    Void.class);
            if (!exchange.getHeaders().containsKey(ACCESS_CONTROL_ALLOW_ORIGIN)) {
                log.info(ACCESS_CONTROL_ALLOW_ORIGIN + " not present in response.");
                throw new RuntimeException("there's no " + ACCESS_CONTROL_ALLOW_ORIGIN
                        + " header present.");
            }
            return exchange;
        });
        HttpHeaders headers = responseEntity.getHeaders();
        headers.forEach((k, v) -> log.info(k + '=' + v.toString()));
        log.info("response received: " + responseEntity.toString());
        Assert.assertTrue("preflight response should contain "
                        + ACCESS_CONTROL_ALLOW_ORIGIN,
                headers.containsKey(ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    private String obtainToken() {

        String authServiceAppId = appNameFromManifest(authServiceManifest);

        URI uri = URI.create(service.urlForApplication(authServiceAppId, HTTPS)
                + "/uaa/oauth/token");
        String username = "jlong";
        String password = "spring";
        String clientSecret = "password";
        String client = "html5";

        LinkedMultiValueMap<String, String> map = new LinkedMultiValueMap<String, String>() {

            {
                add("client_secret", clientSecret);
                add("client_id", client);
                add("scope", "openid");
                add("grant_type", "password");
                add("username", username);
                add("password", password);
            }
        };

        String token = Base64Utils.encodeToString((client + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        RequestEntity<LinkedMultiValueMap<String, String>> requestEntity = RequestEntity
                .post(uri).accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Basic " + token).body(map);
        ParameterizedTypeReference<Map<String, String>> type = new ParameterizedTypeReference<Map<String, String>>() {
        };
        String accessToken = retryTemplate.execute((ctx) -> {
            ResponseEntity<Map<String, String>> responseEntity = restTemplate
                    .exchange(requestEntity, type);
            Map<String, String> body = responseEntity.getBody();
            return body.get("access_token");
        });
        log.info("access_token: " + accessToken);
        return accessToken;
    }

    private void testEdgeRestClient(String testName, String urlSuffix)
            throws Throwable {
        String root = service.urlForApplication(appNameFromManifest(edgeServiceManifest), HTTPS) + "";
        String edgeServiceUrl = root + urlSuffix + testName;
        String healthUrl = root + "/health";
        ResponseEntity<String> responseEntity = restTemplate.getForEntity(
                healthUrl, String.class);
        log.info("health endpoint: " + responseEntity.getBody());
        String body = retryTemplate.execute((RetryCallback<String, Throwable>) context -> {
            ResponseEntity<String> response = restTemplate.getForEntity(edgeServiceUrl, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                String msg = "couldn't get a valid response calling the edge service ";
                log.info(msg);
                throw new RuntimeException(msg + edgeServiceUrl);
            }
            return response.getBody();
        });
        Assert.assertTrue(body.contains("Hello, " + testName));
    }

    private void destroy() {
/*
        log.info("destroy()");
        String authServiceAppId = appNameFromManifest(authServiceManifest);
        String eurekaAppId = appNameFromManifest(eurekaManifest);
        String html5AppId = appNameFromManifest(html5ClientManifest);
        String edgeServiceAppId = appNameFromManifest(edgeServiceManifest);
        String greetingsServiceAppId = appNameFromManifest(greetingsServiceManifest);
        Stream.of(html5AppId, edgeServiceAppId, greetingsServiceAppId, eurekaAppId, authServiceAppId)
                .forEach(appId -> {
                    try {
                        service.destroyApplicationIfExists(appId);
                        log.info("attempted to delete application " + appId);
                    } catch (Throwable ignored) {
                    }
                });

        Stream.of(eurekaAppId, authServiceAppId).forEach(svcId -> {
            try {
                service.destroyServiceIfExists(svcId);
                log.info("attempted to delete service " + svcId);
            } catch (Throwable ignored) {
            }
        });
*/
    }

    private void setEnvironmentVariable(String appId, String k, String v) {
        log.info("set-env " + appId + " " + k + " " + v);
        cloudFoundryOperations
                .applications()
                .setEnvironmentVariable(
                        SetEnvironmentVariableApplicationRequest.builder().name(appId)
                                .variableName(k).variableValue(v).build()).block();
    }

    private void reconfigureApplicationProfile(String appId, String profiles[]) {
        String profileVarName = "spring_profiles_active".toUpperCase();
        String profilesString = StringUtils
                .arrayToCommaDelimitedString(profiles(profiles));
        setEnvironmentVariable(appId, profileVarName, profilesString);
    }

    private void restart(String appId) {
        cloudFoundryOperations.applications()
                .restart(RestartApplicationRequest.builder().name(appId).build()).block();
        log.info("restarted " + appId);
    }

    private static String[] profiles(String... profiles) {
        Collection<String> p = new ArrayList<>();
        if (null != profiles && 0 != profiles.length) {
            p.addAll(Arrays.asList(profiles));
        }
        p.add("cloud");
        return p.toArray(new String[p.size()]);
    }

    private void deployAppAndServiceIfDoesNotExist(File manifest) {
/*
        String appName = appNameFromManifest(manifest);
        log.info("deploying " + appName);
        service
                .applicationManifestFrom(manifest)
                .entrySet()
                .stream()
                .map(e -> {
                    if (!service.applicationExists(appName)) {
                        // service.destroyServiceIfExists(appId);
                        service.pushApplicationAndCreateUserDefinedServiceUsingManifest(e.getKey(),
                                e.getValue());
                        log.info("deployed " + appName + ".");
                    }
                    return appName;
                }).findAny().orElse(null);
*/
    }

    private String deployAppIfDoesNotExist(File manifest) {
        String appName = appNameFromManifest(manifest);
/*        log.info("deploying " + appName);

        service.applicationManifestFrom(manifest).entrySet().stream().map(e -> {
            File f = e.getKey();
            ApplicationManifest am = e.getValue();
            String appId = am.getName();
            if (!service.applicationExists(appId)) {
                service.pushApplicationUsingManifest(f, am, true);
                log.info("deployed " + appName + ".");
            }
            return appId;
        }).findAny().orElse(null);*/
        return appName;
    }

    private void baseline(boolean delete) throws Throwable {
        root = new File(".");
        authServiceManifest = new File(root, "../auth-service/manifest.yml");
        eurekaManifest = new File(root, "../service-registry/manifest.yml");
        edgeServiceManifest = new File(root, "../edge-service/manifest.yml");
        greetingsServiceManifest = new File(root, "../greetings-service/manifest.yml");
        html5ClientManifest = new File(root, "../html5-client/manifest.yml");

        Assert.assertTrue(authServiceManifest.exists());
        Assert.assertTrue(html5ClientManifest.exists());
        Assert.assertTrue(greetingsServiceManifest.exists());
        Assert.assertTrue(eurekaManifest.exists());
        Assert.assertTrue(edgeServiceManifest.exists());

        if (delete) {
            destroy();
        }
    }

    private String appNameFromManifest(File a) {
        /*return service.applicationManifestFrom(a).entrySet().stream()
                .map(e -> e.getValue().getName()).findAny().orElse(null);*/
        return service.getNameForManifest(a);
    }

    public interface ApplicationInstanceConfiguration {

        void configure(String appId);
    }

    private void baselineDeploy(
            // greetings-service
            String[] gsProfiles, Map<String, String> gsEnv,
            ApplicationInstanceConfiguration gsCallback,

            // edge-service
            String[] esProfiles, Map<String, String> esEnv,
            ApplicationInstanceConfiguration esCallback
    ) {
        // backing services
        deployBackingServices();

        deployAppAndServiceIfDoesNotExist(eurekaManifest);
        deployAppAndServiceIfDoesNotExist(authServiceManifest);
        deployAppWithSettings(greetingsServiceManifest, gsProfiles, gsEnv,
                gsCallback);
        deployAppWithSettings(edgeServiceManifest, esProfiles, esEnv,
                esCallback);
        deployAppIfDoesNotExist(html5ClientManifest);

    }

    private void deployBackingServices() {
        /*service.createServiceIfMissing("elephantsql", "turtle", "auth-service-pgsql");*/
    }

    private void deployAppWithSettings(File ma, String[] profiles,
            Map<String, String> env, ApplicationInstanceConfiguration callback) {
        String appId = deployAppIfDoesNotExist(ma);
        if (null != callback) {
            callback.configure(appId);
        }
        reconfigureApplicationProfile(appId, profiles);
        env.forEach((k, v) -> setEnvironmentVariable(appId, k, v));
        restart(appId);
    }

}
