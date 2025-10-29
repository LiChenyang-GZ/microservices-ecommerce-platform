package comp5348.storeservice.model;

public enum OrderStatus {
    PLACED("已下單"),
    PENDING_PAYMENT("待付款"),
    PAID("已付款"),
    PROCESSING("處理中"),
    SHIPPED("已發貨"),
    IN_TRANSIT("配送中"),
    DELIVERED("已送達"),
    CANCELLED("已取消"),
    REFUNDED("已退款");
    
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
