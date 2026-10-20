package comp5348.storeservice.model;

public enum OrderStatus {
    PENDING_STOCK_HOLD("PENDING_STOCK_HOLD"),
    PLACED("Order Placed"),
    PENDING_PAYMENT("Pending Payment"),
    PAID("Paid"),
    PROCESSING("Processing"),
    PICKED_UP("Picked Up"),
    IN_TRANSIT("In Transit"),
    DELIVERED("Delivered"),
    CANCELLED("Cancelled"),
    CANCELLED_SYSTEM("Delivery Service Down"),
    // Wave 2: delivery reported the parcel lost; a REFUND task is waiting (timeline B)
    LOST("Lost"),
    // The refund for a lost parcel went through. Cancelled orders stay CANCELLED after their refund.
    REFUNDED("Refunded");
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
