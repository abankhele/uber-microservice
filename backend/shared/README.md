# 🚖 Distributed Ride-Hailing System

A microservices-based ride-hailing platform built with Spring Boot, implementing the SAGA pattern for distributed transactions and an event-driven architecture with Apache Kafka.

---

## 📋 Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Features](#features)
4. [Key Patterns & Skills](#key-patterns--skills)
5. [Technology Stack](#technology-stack)
6. [Getting Started](#getting-started)
7. [Advanced Patterns](#advanced-patterns)
8. [License](#license)

---

## 📝 Overview

This project showcases a scalable, resilient ride-hailing system composed of independent microservices. It demonstrates best practices in distributed transactions, event sourcing, and fault tolerance.

---

## 🏗️ Architecture

```
Customer Service ←→ Payment Service ←→ Driver Service
       ↓                 ↓                ↓
            Apache Kafka Event Bus
                    ↓
              PostgreSQL Database
```

* **Customer Service**: Manages ride requests and user profiles
* **Payment Service**: Handles secure payment processing and refunds
* **Driver Service**: Manages driver availability and assignments
* **Event Bus**: Apache Kafka for all inter-service events
* **Database**: PostgreSQL for each service’s state persistence

---

## 🔥 Features

* **Ride Management**:

    * Create, update, track, and complete rides
* **Payment Processing**:

    * Secure handling of payments & refunds
* **Driver Assignment**:

    * Intelligent matching of drivers to riders
* **Real-Time Updates**:

    * Event-driven notifications and status tracking
* **Error Handling**:

    * Automatic compensation in case of failures

---

## 🎯 Key Patterns & Skills Demonstrated

### 🔄 SAGA Pattern

* **Distributed Transactions**: Coordinates operations across multiple services
* **Compensation Logic**: Rolls back or compensates for partial failures
* **Event Orchestration**: Manages multi-step business workflows

### 📤 Outbox Pattern

* **Reliable Messaging**: Guarantees no message is lost
* **Transactional Consistency**: Atomic DB + message writes
* **Eventual Consistency**: Keeps services in sync over time

### 🎭 Event-Driven Architecture

* **Asynchronous Communication**: Decoupled service interactions
* **Loose Coupling**: Services can evolve independently
* **Fault Tolerance**: Isolated failures do not cascade

### 🏛️ Domain-Driven Design

* **Bounded Contexts**: Clear separation of domain logic
* **Aggregates**: Enforce business invariants
* **Repository Pattern**: Clean data access layer

---

## 🚀 Technology Stack

* **Java 17** & **Spring Boot**
* **Apache Kafka** for event streaming
* **PostgreSQL** as the relational database
* **Docker** for containerization
* **Maven** for build automation

---

## 🛠️ Getting Started

1. **Clone the repo**

   ```bash
   git clone https://github.com/your-username/ride-hailing-system.git
   cd ride-hailing-system
   ```

2. **Run with Docker Compose**

   ```bash
   docker-compose up --build
   ```

3. **Access Services**

    * Customer Service: `http://localhost:8081`
    * Payment Service: `http://localhost:8082`
    * Driver Service: `http://localhost:8083`
    * Kafka Broker: `localhost:9092`

---

## 🚧 Advanced Patterns

* **Event Sourcing**: Immutable log of all state changes
* **CQRS**: Separation of read and write models
* **Circuit Breaker**: Resilience against downstream failures
* **Optimistic Locking**: Concurrency control

---

## 📄 License

This project is released under the MIT License. See [LICENSE](LICENSE) for details.
