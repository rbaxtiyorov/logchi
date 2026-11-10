package sample;

import io.github.rbaxtiyorov.logchi.core.Channels;
import io.github.rbaxtiyorov.logchi.spring.annotation.Log;
import io.github.rbaxtiyorov.logchi.spring.annotation.Logged;
import io.github.rbaxtiyorov.logchi.spring.annotation.Mask;
import org.slf4j.Logger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class SampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleApplication.class, args);
    }

    @Service
    @Logged(slowerThan = "100ms")
    public static class OrderService {

        @Log
        private Logger log;

        public String create(String item, @Mask String card) {
            log.atInfo().addKeyValue("item", item).addKeyValue("amount", 99.5).log("order created");
            Channels.get("audit").info("order approved item={}", item);
            return "order-for-" + item;
        }

        public String slow() throws InterruptedException {
            Thread.sleep(150);
            return "finally";
        }

        public String fail() {
            throw new IllegalStateException("payment gateway down, token=abc123");
        }
    }

    @RestController
    public static class OrderController {

        private final OrderService orders;

        public OrderController(OrderService orders) {
            this.orders = orders;
        }

        @GetMapping("/orders")
        public String create(@RequestParam String item, @RequestParam String card) {
            return orders.create(item, card);
        }

        @GetMapping("/slow")
        public String slow() throws InterruptedException {
            return orders.slow();
        }

        @GetMapping("/fail")
        public String fail() {
            return orders.fail();
        }
    }
}
