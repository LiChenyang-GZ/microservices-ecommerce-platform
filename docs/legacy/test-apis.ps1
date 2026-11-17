#!/usr/bin/env powershell

# API Test Script for Admin Dashboard

$baseUrl = "http://localhost:8082/api"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Admin Dashboard API Test" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Test 1: Orders API
Write-Host "1. Testing Orders API..." -ForegroundColor Yellow
try {
    $response = Invoke-WebRequest -Uri "$baseUrl/orders" -Method Get -ErrorAction Stop
    $json = $response.Content | ConvertFrom-Json
    Write-Host "✓ Orders API: Success" -ForegroundColor Green
    Write-Host "  - Records: $($json.orders.Count)" -ForegroundColor Gray
    Write-Host "  - Response field: 'orders'" -ForegroundColor Gray
} catch {
    Write-Host "✗ Orders API: Failed" -ForegroundColor Red
}
Write-Host ""

# Test 2: Products API
Write-Host "2. Testing Products API..." -ForegroundColor Yellow
try {
    $response = Invoke-WebRequest -Uri "$baseUrl/products" -Method Get -ErrorAction Stop
    $json = $response.Content | ConvertFrom-Json
    Write-Host "✓ Products API: Success" -ForegroundColor Green
    Write-Host "  - Records: $($json.products.Count)" -ForegroundColor Gray
    Write-Host "  - Response field: 'products'" -ForegroundColor Gray
} catch {
    Write-Host "✗ Products API: Failed" -ForegroundColor Red
}
Write-Host ""

# Test 3: Warehouses API (original)
Write-Host "3. Testing Warehouses API (original)..." -ForegroundColor Yellow
try {
    $response = Invoke-WebRequest -Uri "$baseUrl/warehouses" -Method Get -ErrorAction Stop
    $json = $response.Content | ConvertFrom-Json
    Write-Host "✓ Warehouses API (original): Success" -ForegroundColor Green
    Write-Host "  - Records: $($json.warehouses.Count)" -ForegroundColor Gray
    Write-Host "  - Response field: 'warehouses'" -ForegroundColor Gray
} catch {
    Write-Host "✗ Warehouses API (original): Failed" -ForegroundColor Red
}
Write-Host ""

# Test 4: Warehouses with Products API (NEW)
Write-Host "4. Testing Warehouses with Products API (NEW)..." -ForegroundColor Yellow
try {
    $response = Invoke-WebRequest -Uri "$baseUrl/warehouses/admin/with-products" -Method Get -ErrorAction Stop
    $json = $response.Content | ConvertFrom-Json
    Write-Host "✓ Warehouses with Products API: Success" -ForegroundColor Green
    if ($json.data) {
        Write-Host "  - Records: $($json.data.Count)" -ForegroundColor Gray
        Write-Host "  - Response field: 'data'" -ForegroundColor Gray
        Write-Host "  - First warehouse: $($json.data[0].warehouseName)" -ForegroundColor Gray
        Write-Host "  - Products in first warehouse: $($json.data[0].totalProductsCount)" -ForegroundColor Gray
        Write-Host "  - Total quantity in first warehouse: $($json.data[0].totalQuantity)" -ForegroundColor Gray
    }
} catch {
    Write-Host "✗ Warehouses with Products API: Failed - $_" -ForegroundColor Red
}
Write-Host ""

# Test 5: Audit Report API
Write-Host "5. Testing Audit Report API..." -ForegroundColor Yellow
try {
    $response = Invoke-WebRequest -Uri "$baseUrl/warehouses/audit-logs/out-transactions" -Method Get -ErrorAction Stop
    $json = $response.Content | ConvertFrom-Json
    Write-Host "✓ Audit Report API: Success" -ForegroundColor Green
    if ($json.data) {
        Write-Host "  - Records: $($json.data.Count)" -ForegroundColor Gray
        Write-Host "  - Response field: 'data'" -ForegroundColor Gray
    }
} catch {
    Write-Host "✗ Audit Report API: Failed" -ForegroundColor Red
}
Write-Host ""

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Test Complete" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
