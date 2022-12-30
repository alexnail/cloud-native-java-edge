package greetings;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.discovery.event.HeartbeatEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Profile("cors")
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class CorsFilter implements Filter {

    private final Log log = LogFactory.getLog(getClass());

    private final Map<String, List<ServiceInstance>> catalog = new ConcurrentHashMap<>();

    private final DiscoveryClient discoveryClient;

    // <1>
    @Autowired
    public CorsFilter(DiscoveryClient discoveryClient) {
        this.discoveryClient = discoveryClient;
        refreshCatalog();
    }

    // <2>
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletResponse response = (HttpServletResponse) res;
        HttpServletRequest request = (HttpServletRequest) req;

        String originHeaderValue = originFor(request);
        if (isClientAllowed(originHeaderValue)) {
            response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, originHeaderValue);
        }

        chain.doFilter(req, res);
    }

    // <3>
    private boolean isClientAllowed(String origin) {
        if (StringUtils.hasText(origin)) {
            URI originUri = URI.create(origin);
            int port = originUri.getPort();
            String match = originUri.getHost() + ':' + (port <= 0 ? 80 : port);

            catalog.forEach((k, v) -> {
                String collect = v
                        .stream()
                        .map(si -> si.getHost() + ':' + si.getPort() + '(' + si.getServiceId() + ')')
                        .collect(Collectors.joining());
            });

            boolean svcMatch = catalog.keySet().stream()
                    .anyMatch(serviceId -> catalog.get(serviceId).stream()
                            .map(si -> si.getHost() + ':' + si.getPort())
                            .anyMatch(hp -> hp.equalsIgnoreCase(match)));
            return svcMatch;
        }
        return false;
    }

    // <4>
    @EventListener(HeartbeatEvent.class)
    public void onHeartbeatEvent(HeartbeatEvent e) {
        refreshCatalog();
    }

    private void refreshCatalog() {
        discoveryClient.getServices()
                .forEach(svc -> catalog.put(svc, discoveryClient.getInstances(svc)));
    }

    @Override
    public void init(FilterConfig filterConfig) {
    }

    @Override
    public void destroy() {
    }

    private String originFor(HttpServletRequest request) {
        return StringUtils.hasText(request.getHeader(HttpHeaders.ORIGIN))
                ? request.getHeader(HttpHeaders.ORIGIN)
                : request.getHeader(HttpHeaders.REFERER);
    }
}
