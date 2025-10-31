package comp5348.storeservice.service;

import comp5348.storeservice.dto.*;
import comp5348.storeservice.model.*;
import comp5348.storeservice.repository.InventoryTransactionRepository;
import comp5348.storeservice.repository.WarehouseProductRepository;
import comp5348.storeservice.repository.WarehouseRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@Transactional
public class WarehouseService {

    private static final Logger logger = LoggerFactory.getLogger(WarehouseService.class);

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private WarehouseProductRepository warehouseProductRepository;

    @Autowired
    private InventoryTransactionRepository inventoryTransactionRepository;

    @Transactional(readOnly = true)
    public List<WarehouseDTO> getAllWarehouses() {
        List<Warehouse> warehouses = warehouseRepository.findAll();
        return warehouses.stream()
                .map(WarehouseDTO::new)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public WarehouseDTO getWarehouseById(Long id) {
        return warehouseRepository.findById(id)
                .map(warehouse -> {
                    WarehouseDTO warehouseDTO = new WarehouseDTO(warehouse);
                    List<WarehouseProduct> warehouseProducts = warehouseProductRepository.findByWarehouseId(warehouse.getId());
                    List<ProductDTO> productDTOs = warehouseProducts.stream()
                            .map(wp -> {
                                ProductDTO productDTO = new ProductDTO();
                                productDTO.setId(wp.getProduct().getId());
                                productDTO.setName(wp.getProduct().getName());
                                productDTO.setPrice(wp.getProduct().getPrice());
                                productDTO.setDescription(wp.getProduct().getDescription());
                                productDTO.setStockQuantity(wp.getQuantity());
                                return productDTO;
                            })
                            .collect(Collectors.toList());
                    warehouseDTO.setProducts(productDTOs);
                    return warehouseDTO;
                })
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public Integer getProductQuantity(Long productId) {
        return warehouseProductRepository.findTotalQuantityByProductId(productId).orElse(0);
    }

    public WarehouseDTO createWarehouse(WarehouseRequest request) {
        try {
            Warehouse warehouse = new Warehouse();
            warehouse.setName(request.getName());
            warehouse.setLocation(request.getLocation());
            warehouse.setModifyTime(LocalDateTime.now());
            Warehouse savedWarehouse = warehouseRepository.save(warehouse);
            return new WarehouseDTO(savedWarehouse);
        } catch (Exception e) {
            logger.error("An error occurred: {}", e.toString());
            return null;
        }
    }

    @Async
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public CompletableFuture<WarehouseProductDTO> updateProductQuantity(ProductQuantityUpdateRequest updates) {
        return CompletableFuture.supplyAsync(() -> {
            for (ProductQuantityUpdateRequest.ProductQuantityUpdate update : updates.getUpdates()) {
                try {
                    WarehouseProduct warehouseProduct = warehouseProductRepository
                            .findByWarehouseIdAndProductId(update.getWarehouseId(), update.getProductId())
                            .orElseThrow(() -> new EntityNotFoundException("WarehouseProduct not found"));

                    warehouseProduct.setQuantity(update.getNewQuantity());
                    warehouseProduct.setModifyTime(LocalDateTime.now());
                    WarehouseProduct savedWarehouseProduct = warehouseProductRepository.save(warehouseProduct);

                    InventoryTransaction transaction = new InventoryTransaction();
                    transaction.setProduct(savedWarehouseProduct.getProduct());
                    transaction.setWarehouse(savedWarehouseProduct.getWarehouse());
                    transaction.setQuantity(update.getNewQuantity());
                    transaction.setTransactionTime(LocalDateTime.now());
                    transaction.setType(InventoryTransactionType.IN);
                    inventoryTransactionRepository.save(transaction);

                    return new WarehouseProductDTO(warehouseProduct);
                } catch (OptimisticLockException e) {
                    logger.error("optimistic lock exception during quantity update: {}", e.getMessage());
                    throw e;
                }
            }
            return null;
        });
    }

    public WarehouseDTO updateWarehouse(Long id, Warehouse warehouseDetails) throws OptimisticLockException {
        return warehouseRepository.findByIdWithLock(id)
                .map(warehouse -> {
                    warehouse.setName(warehouseDetails.getName());
                    warehouse.setLocation(warehouseDetails.getLocation());
                    try {
                        warehouse.setModifyTime(LocalDateTime.now());
                        Warehouse updatedWarehouse = warehouseRepository.save(warehouse);
                        return new WarehouseDTO(updatedWarehouse);
                    } catch (OptimisticLockException e) {
                        logger.error("optimistic lock exception during warehouse update: {}", e.getMessage());
                        throw e;
                    } catch (Exception e) {
                        logger.error("failed to update warehouse: {}", e.getMessage());
                        return null;
                    }
                })
                .orElse(null);
    }

    public Boolean deleteWarehouse(Long id) throws OptimisticLockException {
        return warehouseRepository.findByIdWithLock(id)
                .map(warehouse -> {
                    try {
                        warehouseRepository.delete(warehouse);
                        return true;
                    } catch (OptimisticLockException e) {
                        logger.error("optimistic lock exception during warehouse deletion: {}", e.getMessage());
                        return false;
                    } catch (Exception e) {
                        logger.error("failed to delete warehouse: {}", e.getMessage());
                        return false;
                    }
                })
                .orElse(false);
    }

    public WarehouseDTO getAndUpdateAvailableWarehouse(long productId, int quantity) {
        List<WarehouseProduct> availableProducts = warehouseProductRepository.findByProductIdAndQuantity(productId);
        List<WarehouseDTO> warehouseDTOs = new ArrayList<>();
        List<Long> inventoryTransactionIds = new ArrayList<>();
        int remainingQuantity = quantity;
        List<WarehouseProduct> updatedProducts = new ArrayList<>();

        for (WarehouseProduct wp : availableProducts) {
            if (remainingQuantity <= 0) break;

            int availableQuantity = wp.getQuantity();
            int quantityToTake = Math.min(availableQuantity, remainingQuantity);

            WarehouseDTO dto = new WarehouseDTO();
            dto.setId(wp.getWarehouse().getId());
            dto.setName(wp.getWarehouse().getName());
            dto.setLocation(wp.getWarehouse().getLocation());
            warehouseDTOs.add(dto);

            remainingQuantity -= quantityToTake;

            WarehouseProduct updatedWp = new WarehouseProduct(wp);
            updatedWp.setQuantity(availableQuantity - quantityToTake);
            updatedProducts.add(updatedWp);

            InventoryTransaction tx = new InventoryTransaction();
            tx.setProduct(wp.getProduct());
            tx.setWarehouse(wp.getWarehouse());
            tx.setQuantity(quantityToTake);
            tx.setType(InventoryTransactionType.HOLD);
            tx.setTransactionTime(LocalDateTime.now());
            InventoryTransaction saved = inventoryTransactionRepository.save(tx);
            inventoryTransactionIds.add(saved.getId());
        }

        if (remainingQuantity > 0) {
            logger.warn("insufficient stock for product: {}, remaining quantity needed: {}", productId, remainingQuantity);
            return null;
        }

        for (WarehouseProduct wp : updatedProducts) {
            warehouseProductRepository.save(wp);
        }

        WarehouseDTO resp = new WarehouseDTO();
        resp.setWarehouses(warehouseDTOs);
        resp.setInventoryTransactionIds(inventoryTransactionIds);
        return resp;
    }

    public boolean unholdProduct(UnholdProductRequest request) {
        List<Long> ids = request.getInventoryTransactionIds();
        if (ids == null || ids.isEmpty()) return false;
        try {
            List<InventoryTransaction> transactions = inventoryTransactionRepository.findAllById(ids);
            for (InventoryTransaction tx : transactions) {
                Optional<WarehouseProduct> wpOpt = warehouseProductRepository.findByWarehouseAndProduct(tx.getWarehouse(), tx.getProduct());
                if (!wpOpt.isPresent()) return false;
                WarehouseProduct wp = wpOpt.get();
                wp.setQuantity(wp.getQuantity() + tx.getQuantity());
                wp.setModifyTime(LocalDateTime.now());
                warehouseProductRepository.save(wp);

                tx.setType(InventoryTransactionType.UNHOLD);
                tx.setTransactionTime(LocalDateTime.now());
                inventoryTransactionRepository.save(tx);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}


