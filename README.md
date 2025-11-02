# Distributed E-Commerce System

## COMP5348 Group Project - Enterprise-Scale Software Development

A fault-tolerant, distributed e-commerce application built with microservices architecture, demonstrating high availability and reliability through various enterprise patterns including Outbox Pattern, Saga Compensation, and Message-Driven Architecture.

---

## Table of Contents

1. [System Architecture](#system-architecture)
2. [Prerequisites](#prerequisites)
3. [Quick Start Guide](#quick-start-guide)
4. [Service Details](#service-details)
5. [Database and Data Initialization](#database-and-data-initialization)
6. [Testing the Application](#testing-the-application)
7. [Fault Tolerance Scenarios](#fault-tolerance-scenarios)
8. [API Documentation](#api-documentation)
9. [Troubleshooting](#troubleshooting)

---

## System Architecture

The system consists of 4 microservices and 1 frontend application:

```
┌─────────────────────────────────────────────────────────────┐
│                    Frontend (React)                          │
│                   http://localhost:3000                      │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ↓
┌──────────────────────────────────────────────────────────────┐
│              Store Service (Spring Boot)                     │
│                 http://localhost:8082                        │
│  • Order Management    • Payment Processing                  │
│  • Warehouse Management • Inventory Control                  │
└───────┬────────────┬─────────────┬─────────────┬────────────┘
        │            │             │             │
        ↓            ↓             ↓             ↓
   ┌────────┐  ┌─────────┐  ┌──────────┐  ┌──────────┐
   │  Bank  │  │Delivery │  │  Email   │  │ RabbitMQ │
   │ :8084  │  │  :8081  │  │  :8083   │  │  :5672   │
   └────────┘  └─────────┘  └──────────┘  └──────────┘
```

### Technology Stack

**Backend:**
- Java 17+
- Spring Boot 3.x
- Spring Data JPA (Hibernate ORM)
- RabbitMQ (Message Queue)
- H2/MySQL Database
- JWT Authentication
- Gradle Build System

**Frontend:**
- React 19
- React Router
- Axios (HTTP Client)
- Context API (State Management)

---

## Prerequisites

Ensure you have the following installed on your system:

- **Java Development Kit (JDK) 17 or higher**
  - Verify: `java -version`
  
- **Node.js 16+ and npm**
  - Verify: `node -v` and `npm -v`
  
- **PostgreSQL Database**
  - Version: PostgreSQL 12+
  - Verify: `psql --version`
  - **Required:** Create database `webuser` before starting Store Service
  
- **Docker and Docker Compose** (for RabbitMQ)
  - Verify: `docker -v` and `docker-compose -v`
  
- **Git** (for cloning the repository)
  - Verify: `git --version`

---

## Quick Start Guide

### Step 0: Setup PostgreSQL Database

Create the required PostgreSQL database before starting the Store Service:

```bash
# Connect to PostgreSQL
psql -U postgres

# Create database
CREATE DATABASE webuser;

# Exit
\q
```

Alternatively, if the database already exists, you can skip this step.

### Step 1: Clone the Repository

```bash
git clone https://github.sydney.edu.au/COMP4348-5348-2025/[your-repo-name].git
cd TUT08-08
```

### Step 2: Start RabbitMQ

RabbitMQ is required for asynchronous messaging between services.

```bash
cd backend
docker-compose up -d
```

Verify RabbitMQ is running:
- Access RabbitMQ Management Console: http://localhost:15672
- Login: `admin` / `admin`

### Step 3: Start Backend Services

Open **4 separate terminal windows** and start each service:

#### Terminal 1 - Bank Service

```bash
cd backend/bankService
./gradlew bootRun
```

Wait for the message: `Started BankApplication in X seconds`

#### Terminal 2 - Email Service

```bash
cd backend/emailService
./gradlew bootRun
```

Wait for the message: `Started EmailApplication in X seconds`

#### Terminal 3 - Delivery Service

```bash
cd backend/deliveryService
./gradlew bootRun
```

Wait for the message: `Started DeliveryApplication in X seconds`

#### Terminal 4 - Store Service (Main Service)

```bash
cd backend/storeService
./gradlew bootRun
```

Wait for the message: `Started StoreServiceApplication in X seconds`

**Service Ports:**
- Bank Service: http://localhost:8084
- Email Service: http://localhost:8083
- Delivery Service: http://localhost:8081
- Store Service: http://localhost:8082

### Step 4: Start Frontend Application

Open a **5th terminal window**:

```bash
cd frontend
npm install        # Only needed for first time
npm start
```

The frontend will automatically open in your browser at http://localhost:3000

---

## Service Details

### 1. Store Service (Main Application)

**Port:** 8082  
**Database:** PostgreSQL (localhost:5432/webuser)  
**Endpoints:** http://localhost:8082/api

**Key Features:**
- Order creation and management
- Payment processing with retry mechanism
- Warehouse and inventory management with optimistic locking
- Outbox pattern for reliable event delivery
- Saga compensation for distributed transactions
- JWT authentication

**Configuration:** `backend/storeService/src/main/resources/application.properties`

**Database Credentials:**
- Username: `(your database username)`
- Password: `(your database password)`
- **Note:** Configure in `application.properties` before running

### 2. Bank Service

**Port:** 8084  
**Database:** H2 (in-memory) - automatically created on startup  
**Endpoints:** http://localhost:8084/api/bank

**Key Features:**
- Account creation and management
- Fund transfer with transaction logging
- Refund processing
- Balance inquiry

**Pre-loaded Accounts:**
- Store Account: `STORE_ACCOUNT_001` (Balance: 100,000)
- Customer accounts are created automatically when users register

### 3. Delivery Service

**Port:** 8081  
**Database:** H2 (in-memory)  
**Endpoints:** http://localhost:8081/api/deliveries

**Key Features:**
- Delivery task creation
- Status updates (CREATED → PICKED_UP → IN_TRANSIT → DELIVERED)
- Package loss simulation (5% probability)
- Webhook notifications to Store Service
- Dead letter queue for failed notifications

### 4. Email Service

**Port:** 8083  
**Database:** H2 (in-memory)  
**Message Queue:** RabbitMQ

**Key Features:**
- Asynchronous email sending via RabbitMQ
- Manual acknowledgment for reliability
- Email verification codes
- Order status notifications
- Refund confirmations

**Note:** Emails are printed to console (not actually sent)

---

## Database and Data Initialization

### Automatic Data Initialization

All services automatically initialize data on startup using Spring Boot's `@PostConstruct` or `DataInitializer` classes.

### Store Service - Initial Data

**File:** `backend/storeService/src/main/java/comp5348/storeservice/config/StoreDataInitializer.java`

**Created Entities:**

1. **Products:** (5 products)
   - iPhone 13 (Price: 6999.00) - A15 Bionic chip, dual camera system
   - iPhone 14 (Price: 7999.00) - Longer battery life, crash detection
   - iPhone 15 (Price: 8999.00) - A16 chip, USB-C, Dynamic Island
   - iPhone 16 (Price: 9999.00) - Enhanced AI experience, camera upgrade
   - iPhone 17 (Price: 10999.00) - Next-generation chip and imaging system

2. **Warehouses:** (3 warehouses)
   - Main Warehouse (Location: Warehouse 1, Chaoyang District, Beijing)
   - Shanghai Warehouse (Location: Warehouse 2, Pudong New Area, Shanghai)
   - Guangzhou Warehouse (Location: Warehouse 3, Tianhe District, Guangzhou)

3. **Warehouse Inventory:**
   - Each product has total inventory of 10 units distributed across warehouses
   - Some warehouses stock certain products, some don't (realistic distribution)
   - Total inventory: 50 items (10 per product across all warehouses)

**Note:** No pre-configured demo user. Users must register a new account to use the system.

### Bank Service - Initial Data

**File:** `backend/bankService/src/main/java/com/comp5348/bank/config/DataInitializer.java`

**Created Accounts:**
- Store Account: `STORE_ACCOUNT_001` (Balance: 100,000)
- Customer Accounts: Auto-created when users register with initial balance of 10,000

### Viewing Database Data

#### PostgreSQL Database Access (Store Service)

**Prerequisites:** PostgreSQL must be installed and running with database `webuser` created.

```bash
# Connect to PostgreSQL database
psql -U postgres -d webuser

# Or use any PostgreSQL client:
# Host: localhost
# Port: 5432
# Database: webuser
# Username: (your database username)
# Password: (your database password)

```

**Available Tables:**
- `account` - User accounts
- `product` - Product catalog (iPhone 13-17)
- `warehouse` - Warehouse locations (Beijing, Shanghai, Guangzhou)
- `warehouse_product` - Inventory (with version for optimistic locking)
- `orders` - Order records
- `payment` - Payment transactions
- `payment_outbox` - Outbox events for reliability
- `inventory_transaction` - Inventory change history

#### Bank Service Database (H2 Console)

```bash
# Access H2 console for Bank Service
# (if H2 console is enabled in Bank Service)
# JDBC URL: jdbc:h2:mem:bankdb
# Username: sa
# Password: (leave empty)
```

---

## Testing the Application

### 1. Basic User Flow

1. **Register a New Account**
   - Navigate to http://localhost:3000/register
   - Enter username, email, password
   - System automatically creates:
     - User account in Store Service
     - Bank account with initial balance of 10,000

2. **Login**
   - Navigate to http://localhost:3000/login
   - Use the account you just registered

3. **Browse Products**
   - Click "View Products" from home page
   - Browse available iPhone models (iPhone 13-17) with stock information

4. **Place an Order**
   - Select an iPhone model
   - Enter quantity (ensure sufficient stock and balance)
   - Click "Checkout"
   - Confirm order creation
   - System will:
     - Reserve inventory
     - Process payment
     - Create delivery
     - Send confirmation emails

5. **Track Order Status**
   - View order in "My Orders" section
   - Check payment status
   - Track delivery status updates

6. **View Deliveries**
   - Navigate to "My Deliveries"
   - See delivery status (CREATED → PICKED_UP → IN_TRANSIT → DELIVERED)

### 2. Cancel Order (Before Pickup)

```bash
# Using curl
curl -X POST http://localhost:8082/api/store/orders/{orderId}/cancel \
  -H "Authorization: Bearer {your-jwt-token}"
```

**Expected Behavior:**
- Delivery cancelled (if status is CREATED)
- Payment refunded
- Inventory released
- Confirmation email sent

### 3. Test with Postman/Thunder Client

Import API endpoints:
- Store API: http://localhost:8082/api
- Bank API: http://localhost:8084/api/bank
- Delivery API: http://localhost:8081/api/deliveries
- Email API: http://localhost:8083/api/email

**Key Endpoints:**

```bash
# Register a new user first
POST http://localhost:8082/api/user
Body: {"username": "testuser", "email": "test@example.com", "password": "password123"}

# Login and get JWT token
POST http://localhost:8082/api/user/login
Body: {"email": "test@example.com", "password": "password123"}

# Get all products
GET http://localhost:8082/api/products

# Get products with stock
GET http://localhost:8082/api/products/available

# Create order
POST http://localhost:8082/api/store/orders/create-with-payment
Body: {
  "userId": 1,
  "orderItems": [
    {"productId": 1, "quantity": 2}
  ]
}

# Get user orders
GET http://localhost:8082/api/store/orders/user/{userId}/with-payment

# Cancel order
POST http://localhost:8082/api/store/orders/{orderId}/cancel
```

---

## Fault Tolerance Scenarios

The system implements robust fault tolerance mechanisms. Here are demonstration scenarios:

### Scenario 1: Bank Service Temporary Failure

**Setup:**
1. Start all services normally
2. Stop Bank Service: Press `Ctrl+C` in Bank Service terminal

**Test:**
```bash
# Attempt to create an order
# The system will retry 3 times (1 second intervals)
```

**Expected Behavior:**
- Store Service attempts payment 3 times
- After 3 failures, creates `PAYMENT_FAILED` outbox event
- Inventory automatically released
- Order status set to `CANCELLED`
- Customer receives failure notification email

**Recovery:**
```bash
# Restart Bank Service
cd backend/bankService
./gradlew bootRun

# Retry the order - it should succeed now
```

### Scenario 2: Delivery Service Failure with Compensation

**Setup:**
1. Create an order and complete payment successfully
2. Stop Delivery Service before pickup

**Expected Behavior:**
- Store Service retries delivery creation 3 times
- Creates `DELIVERY_FAILED` outbox event
- **Waits 30 seconds** (compensation delay)
- After 30 seconds:
  - Triggers automatic refund
  - Releases inventory
  - Updates order status to `CANCELLED_SYSTEM`
  - Sends refund confirmation email

**View Logs:**
```bash
# Store Service logs show:
[OutboxProcessor] Processing DELIVERY_FAILED event for order X
[OutboxProcessor] Waiting for service recovery... (XX seconds elapsed)
[CompensationService] Triggering compensation for order X
[PaymentService] Refund processed successfully
```

### Scenario 3: Concurrent Orders (Optimistic Locking)

**Setup:**
1. Set product inventory to 1 (can modify via H2 console)
2. Simulate concurrent orders from 2 users

**Expected Behavior:**
- First order succeeds and reserves inventory
- Second order fails with `OptimisticLockException`
- No inventory overselling
- Failed user receives "Out of stock" error

**Test with curl:**
```bash
# Terminal 1
curl -X POST http://localhost:8082/api/store/orders/create-with-payment \
  -H "Content-Type: application/json" \
  -d '{"userId": 1, "orderItems": [{"productId": 1, "quantity": 1}]}' &

# Terminal 2 (run immediately)
curl -X POST http://localhost:8082/api/store/orders/create-with-payment \
  -H "Content-Type: application/json" \
  -d '{"userId": 2, "orderItems": [{"productId": 1, "quantity": 1}]}' &
```

### Scenario 4: RabbitMQ Failure (Outbox Pattern)

**Setup:**
1. Stop RabbitMQ: `docker-compose down`
2. Create an order

**Expected Behavior:**
- Events stored in `payment_outbox` table (database persistence)
- Order processing continues normally
- When RabbitMQ restarts:
  - Outbox processor (runs every 5 seconds) detects pending events
  - Automatically sends events to queue
  - Email notifications delivered

**Recovery:**
```bash
docker-compose up -d
# Wait 5-10 seconds for outbox processor to scan
# Check email service logs for message processing
```

### Scenario 5: Delivery Package Loss

**Setup:**
1. Create order normally
2. Wait for delivery status updates

**Expected Behavior (5% probability):**
- Delivery status may become `LOST`
- System automatically triggers compensation:
  - Refund processed
  - Order status set to `CANCELLED`
  - Customer receives notification emails

---

## API Documentation

### Authentication

All protected endpoints require JWT token in header:

```bash
Authorization: Bearer {jwt-token}
```

**Get Token:**
```bash
POST http://localhost:8082/api/user/login
Body: {"email": "customer@example.com", "password": "COMP5348"}
Response: {"token": "eyJhbGc...", "userId": 1, "email": "..."}
```

### Store Service API

#### User Management
```bash
POST   /api/user              # Create account
POST   /api/user/login        # Login
POST   /api/user/activate     # Activate account
```

#### Products
```bash
GET    /api/products                  # Get all products
GET    /api/products/available        # Get products with stock
GET    /api/products/{id}             # Get product by ID
GET    /api/products/search?name=X    # Search by name
```

#### Orders
```bash
POST   /api/store/orders/create-with-payment    # Create order + payment
GET    /api/store/orders/user/{userId}/with-payment   # Get user orders
GET    /api/store/orders/{orderId}/with-payment       # Get order details
POST   /api/store/orders/{orderId}/cancel             # Cancel order
```

#### Payments
```bash
GET    /api/payments/order/{orderId}      # Get payment by order ID
POST   /api/payments/{orderId}/refund     # Request refund
```

### Bank Service API

```bash
POST   /api/bank/account           # Create account
GET    /api/bank/account/{accountNumber}/balance    # Get balance
POST   /api/bank/transfer          # Transfer funds
POST   /api/bank/refund            # Refund transaction
```

### Delivery Service API

```bash
GET    /api/deliveries/me                 # Get my deliveries
GET    /api/deliveries/{id}               # Get delivery details
POST   /api/deliveries/{id}/cancel        # Cancel delivery
```

### Email Service API

```bash
POST   /api/email/send-verification       # Send verification code
POST   /api/email/verify-code             # Verify code
```

---

## Troubleshooting

### Issue: Port Already in Use

**Error:** `Port 8082 is already in use`

**Solution:**
```bash
# Find process using the port (macOS/Linux)
lsof -ti:8082

# Kill the process
kill -9 $(lsof -ti:8082)

# Windows
netstat -ano | findstr :8082
taskkill /PID <process-id> /F
```

### Issue: RabbitMQ Connection Refused

**Error:** `Connection refused: localhost:5672`

**Solution:**
```bash
# Check if RabbitMQ container is running
docker ps

# If not running, start it
cd backend
docker-compose up -d

# Check logs
docker logs rabbitmq-5348
```

### Issue: H2 Database Lock Error

**Error:** `Database may be already in use`

**Solution:**
```bash
# Stop all Spring Boot services
# Remove H2 database files (if using file-based mode)
rm -rf ~/.h2/
# Restart services
```

### Issue: Gradle Build Fails

**Error:** `Could not resolve dependencies`

**Solution:**
```bash
# Clear Gradle cache
./gradlew clean --refresh-dependencies

# Or delete cache manually
rm -rf ~/.gradle/caches/
```

### Issue: Frontend Cannot Connect to Backend

**Error:** `Network Error` or CORS error

**Solution:**
1. Verify all backend services are running
2. Check `WebConfig.java` CORS configuration allows `http://localhost:3000`
3. Clear browser cache and hard refresh (Ctrl+Shift+R)

### Issue: JWT Token Invalid

**Error:** `401 Unauthorized`

**Solution:**
1. Get new token by logging in again
2. Ensure token is not expired (default 24 hours)
3. Check `Authorization` header format: `Bearer {token}`

---

## Project Structure

```
TUT08-08/
├── backend/
│   ├── bankService/          # Bank payment service
│   │   ├── src/main/java/
│   │   ├── src/main/resources/
│   │   └── build.gradle
│   ├── deliveryService/      # Delivery management service
│   │   ├── src/main/java/
│   │   ├── src/main/resources/
│   │   └── build.gradle
│   ├── emailService/         # Email notification service
│   │   ├── src/main/java/
│   │   ├── src/main/resources/
│   │   └── build.gradle
│   ├── storeService/         # Main store service
│   │   ├── src/main/java/
│   │   │   └── comp5348/storeservice/
│   │   │       ├── adapter/        # External service integration
│   │   │       ├── config/         # Configuration classes
│   │   │       ├── controller/     # REST controllers
│   │   │       ├── dto/            # Data transfer objects
│   │   │       ├── model/          # JPA entities
│   │   │       ├── repository/     # Data access layer
│   │   │       ├── scheduler/      # Scheduled tasks
│   │   │       ├── service/        # Business logic
│   │   │       └── utils/          # Utility classes
│   │   ├── src/main/resources/
│   │   │   ├── application.properties
│   │   │   ├── data.sql            # SQL initialization
│   │   │   └── logback-spring.xml  # Logging config
│   │   └── build.gradle
│   ├── compose.yml               # Docker Compose for RabbitMQ
│   ├── gradlew                   # Gradle wrapper
│   └── settings.gradle
├── frontend/
│   ├── src/
│   │   ├── components/           # Reusable components
│   │   ├── contexts/             # React contexts
│   │   ├── pages/                # Page components
│   │   ├── services/             # API services
│   │   ├── utils/                # Utility functions
│   │   ├── App.js                # Main app component
│   │   └── index.js              # Entry point
│   ├── public/                   # Static assets
│   └── package.json
└── README.md                     # This file
```

---

## Key Design Patterns Implemented

1. **Outbox Pattern** - Reliable event delivery even with message broker failures
2. **Saga Pattern** - Distributed transaction management with compensation
3. **Retry Pattern** - Automatic retry for transient failures
4. **Circuit Breaker Pattern** - Graceful degradation (implicit through retry limits)
5. **Adapter Pattern** - External service integration abstraction
6. **Repository Pattern** - Data access abstraction
7. **DTO Pattern** - Layer separation and data transfer

---

## Quality Attributes

- **Availability**: 99%+ through retry mechanisms and outbox pattern
- **Reliability**: Eventual consistency guaranteed through compensation
- **Scalability**: Microservices can be scaled independently
- **Performance**: Asynchronous processing, lazy loading, connection pooling
- **Security**: JWT authentication, BCrypt password hashing, CORS protection
- **Maintainability**: Clear layered architecture, separation of concerns

---

## Demo Credentials

**User Account:**
- No pre-configured demo account
- Register a new account at http://localhost:3000/register
- New users automatically get a bank account with 10,000 initial balance

**PostgreSQL Database:**
- Host: localhost:5432
- Database: `webuser`
- Username: `(your database username)`
- Password: `(your database password)`
- **Note:** Configure in `application.properties` before running

**RabbitMQ Management:**
- URL: http://localhost:15672
- Username: `admin`
- Password: `admin`



