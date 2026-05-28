package com.example.order;

public class OrderController {

    private final OrderSubmitService orderSubmitService;

    public OrderController(OrderSubmitService orderSubmitService) {
        this.orderSubmitService = orderSubmitService;
    }

    public OrderSubmitResponse submit(OrderSubmitRequest request) {
        return orderSubmitService.submit(request);
    }

    public OrderSubmitHttpResponse submitHttp(OrderSubmitRequest request) {
        try {
            if (request.getRequestId() != null && !request.getRequestId().isBlank()) {
                return OrderSubmitHttpResponse.ok(orderSubmitService.submitFlashSale(request));
            }
            return OrderSubmitHttpResponse.ok(orderSubmitService.submit(request));
        } catch (IllegalArgumentException e) {
            return OrderSubmitHttpResponse.badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return OrderSubmitHttpResponse.badRequest(e.getMessage());
        }
    }
}
