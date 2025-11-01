package comp5348.storeservice.model;

public enum OrderStatus {
    PENDING_STOCK_HOLD("PENDING_STOCK_HOLD"),
    PLACED("Order Placed"),
    PENDING_PAYMENT("Pending Payment"),
    PAID("Paid"),
    PROCESSING("Processing"),
    SHIPPED("Shipped"),
    IN_TRANSIT("In Transit"),
    DELIVERED("Delivered"),
    CANCELLED("Cancelled"),
    REFUNDED("Refunded"),
    LOST("Package Lost"),
    CANCELLED_SYSTEM("Delivery Service Down");
    private final String description;
    
    OrderStatus(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
    
    @Override
    public String toString() {
        return description;
    }
}
