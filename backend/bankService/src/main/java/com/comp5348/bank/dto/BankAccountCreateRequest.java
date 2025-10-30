package com.comp5348.bank.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class BankAccountCreateRequest {
    private String ownerEmail; // 或者 ownerName
    private BigDecimal initialBalance;
}


