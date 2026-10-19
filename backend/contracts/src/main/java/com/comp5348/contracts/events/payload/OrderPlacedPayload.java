package com.comp5348.contracts.events.payload;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;

public record OrderPlacedPayload(
        long userId,
        long productId,
        int quantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAmount) {
}
