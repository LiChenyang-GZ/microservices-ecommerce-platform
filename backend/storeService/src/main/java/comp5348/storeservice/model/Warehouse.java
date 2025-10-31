package comp5348.storeservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "warehouses")
public class Warehouse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 200)
    private String location;

    @Version
    private Long version;

    @Column(name = "modify_time")
    private LocalDateTime modifyTime;
//
//    @PrePersist
//    protected void onCreate() {
//        modifyTime = LocalDateTime.now();
//    }
//
//    @PreUpdate
//    protected void onUpdate() {
//        modifyTime = LocalDateTime.now();
//    }
}


