package comp5348.storeservice.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "warehouse_products")
public class WarehouseProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "warehouse_id")
    @JsonBackReference
    private Warehouse warehouse;

    @ManyToOne
    @JoinColumn(name = "product_id")
    @JsonBackReference
    private Product product;

    @Column(nullable = false)
    private Integer quantity;

    @Version
    private Long version;

    @Column(name = "modify_time")
    private LocalDateTime modifyTime;

    @PrePersist
    protected void onCreate() {
        modifyTime = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        modifyTime = LocalDateTime.now();
    }

    public WarehouseProduct(WarehouseProduct other) {
        this.id = other.id;
        this.warehouse = other.warehouse;
        this.product = other.product;
        this.quantity = other.quantity;
        this.version = other.version;
        this.modifyTime = other.modifyTime;
    }
}


