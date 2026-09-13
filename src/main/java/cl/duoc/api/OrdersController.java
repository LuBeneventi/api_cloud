package cl.duoc.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@RestController
public class OrdersController {

    private final RestClient ordersServiceClient;

    OrdersController(RestClient ordersServiceClient) {
        this.ordersServiceClient = ordersServiceClient;
    }

    @GetMapping("/api/orders")
    public String orders() {
        return ordersServiceClient
            .get()
            .uri("/api/orders")
            .retrieve()
            .body(String.class);
    }
}