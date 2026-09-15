package cl.duoc.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@RestController
public class OrdersController {

    private final RestClient ordersServiceClient;

    OrdersController(RestClient ordersServiceClient) {
        this.ordersServiceClient = ordersServiceClient;
    }

    @GetMapping("/api/orders")
    public String orders(HttpServletRequest request) {
        String queryString = request.getQueryString();
        String targetUri = "/api/orders" + (queryString != null && !queryString.isBlank() ? "?" + queryString : "");
        return ordersServiceClient
            .get()
            .uri(targetUri)
            .retrieve()
            .body(String.class);
    }

    @GetMapping("/api/orders/{id}")
    public String getOrderById(@PathVariable String id) {
        return ordersServiceClient
            .get()
            .uri("/api/orders/" + id)
            .retrieve()
            .body(String.class);
    }
}