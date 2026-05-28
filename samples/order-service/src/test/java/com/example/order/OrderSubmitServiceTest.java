package com.example.order;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class OrderSubmitServiceTest {

    @Test
    void testSubmitSuccess() {
        OrderRepository orderRepository = new OrderRepository();
        OrderSubmitService service = new OrderSubmitService(orderRepository);
        OrderSubmitRequest request = new OrderSubmitRequest("user1", "sku1", 2, new BigDecimal("10.00"));
        OrderSubmitResponse response = service.submit(request);
        assertNotNull(response);
        assertNotNull(response.getOrderId());
        assertEquals(new BigDecimal("20.00"), response.getTotalAmount());
        assertEquals(1, orderRepository.countCreatedOrders());
    }

    @Test
    void testSubmitNullUserId() {
        OrderRepository orderRepository = new OrderRepository();
        OrderSubmitService service = new OrderSubmitService(orderRepository);
        OrderSubmitRequest request = new OrderSubmitRequest(null, "sku1", 1, new BigDecimal("10.00"));
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> service.submit(request));
        assertTrue(thrown.getMessage().contains("UserId"));
    }

    @Test
    void testSubmitNullSkuId() {
        OrderRepository orderRepository = new OrderRepository();
        OrderSubmitService service = new OrderSubmitService(orderRepository);
        OrderSubmitRequest request = new OrderSubmitRequest("user1", null, 1, new BigDecimal("10.00"));
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> service.submit(request));
        assertTrue(thrown.getMessage().contains("skuId"));
    }

    @Test
    void testSubmitNullUnitPrice() {
        OrderRepository orderRepository = new OrderRepository();
        OrderSubmitService service = new OrderSubmitService(orderRepository);
        OrderSubmitRequest request = new OrderSubmitRequest("user1", "sku1", 1, null);
        assertThrows(IllegalArgumentException.class, () -> service.submit(request));
    }

    @Test
    void testSubmitNullQuantity() {
        OrderRepository orderRepository = new OrderRepository();
        OrderSubmitService service = new OrderSubmitService(orderRepository);
        OrderSubmitRequest request = new OrderSubmitRequest("user1", "sku1", null, new BigDecimal("10.00"));
        assertThrows(IllegalArgumentException.class, () -> service.submit(request));
    }
}

