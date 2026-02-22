# Pocket Lumen - Mobile Log Viewer Backend

MVP Backend for streaming Docker container logs.

## 🚀 Features
- **REST API**: List all running containers.
- **WebSocket**: Real-time log and stats streaming.
- **Full Management**: Start, stop, restart, and delete containers.
- **Resource Explorer**: List images, volumes, and networks.
- **Detailed Inspection**: View environment variables, ports, and mounts for containers.
- **Alert History**: Track resource usage alerts over time.
- **Email Notifications**: Get notified via Gmail (SMTP) when CPU usage exceeds thresholds.
- **Interactive Documentation**: Swagger UI for exploring and testing API endpoints.
- **Secure Configuration**: Uses `.env` for sensitive credentials.

## 🛠 Tech Stack
- **Java 21**
- **Spring Boot 4.0.2**
- **SpringDoc OpenAPI** (Swagger UI)
- **Docker Java Client** (com.github.docker-java)
- **WebSockets**
- **Thymeleaf** (Email templates)
- **Spring Mail** (SMTP integration)

## 🔌 API Contract

### REST API

#### Containers
- **`GET /containers`**: List all containers (running and stopped).
- **`GET /containers/{id}`**: Get detailed information about a container.
- **`POST /containers/{id}/start`**: Start a container.
- **`POST /containers/{id}/stop`**: Stop a container.
- **`POST /containers/{id}/restart`**: Restart a container.
- **`DELETE /containers/{id}`**: Remove a container (forced).

#### Alerts
- **`GET /alerts/history`**: Get list of all resource alerts.
- **`DELETE /alerts/history`**: Clear alert history.
- **`GET /alerts/settings`**: Get current notification settings.
- **`POST /alerts/settings`**: Update settings (JSON: `{"notificationsEnabled": true, "recipientEmail": "user@gmail.com"}`).

#### System Resources
- **`GET /containers/images`**: List all Docker images.
- **`GET /containers/volumes`**: List all Docker volumes.
- **`GET /containers/networks`**: List all Docker networks.

---

### WebSocket API
- **`WS /logs?containerId={id}`**: Stream container logs.
- **`WS /stats?containerId={id}&email={userEmail}`**: Stream real-time statistics and send alerts to the specified email if thresholds are exceeded.

## ⚙️ Configuration

1. **Environment Variables**: Create a `.env` file from `env.example`.
2. **Mail Server**: Configure your SMTP settings (e.g., Mailgun or Gmail) in `.env`.
3. **Thresholds**: Adjust `ALERT_CPU_THRESHOLD` and `ALERT_COOLDOWN_MINUTES` in `.env`.

The application connects to the Docker Engine using the host defined in `DOCKER_HOST`.
- **Linux/macOS**: `unix:///var/run/docker.sock`
- **Windows**: `npipe:////./pipe/docker_engine`

## 🏃 How to Run

### Prerequisites
- Docker installed and running.
- Java 21 installed.
- Maven installed (or use `./mvnw`).

### Steps
1. **Clone the repository.**
2. **Create `.env` file**:
   ```bash
   cp env.example .env
   # Edit .env with your real SMTP credentials
   ```
3. **Build the application:**
   ```bash
   ./mvnw clean package
   ```
4. **Run the application:**
   ```bash
   java -jar target/lumen-mobile-app-1.jar
   ```
   Or using Maven:
   ```bash
   ./mvnw spring-boot:run
   ```

The backend will start on `http://localhost:8324`.

## 📖 API Documentation (Swagger)

Once the application is running, you can access the interactive API documentation at:
- **Swagger UI**: [http://localhost:8324/swagger-ui.html](http://localhost:8324/swagger-ui.html)
- **OpenAPI Spec**: [http://localhost:8324/v3/api-docs](http://localhost:8324/v3/api-docs)

## 🧪 Testing Locally

### Using `curl` (REST API)
```bash
curl http://localhost:8324/containers
```

### Using `wscat` (WebSocket)
1. Install `wscat`: `npm install -g wscat`
2. Connect:
```bash
wscat -c "ws://localhost:8324/logs?containerId=<YOUR_CONTAINER_ID>"
```

## 🏗 Infrastructure Details

- **`DockerConfig`**: Configures the `DockerClient` with `ApacheDockerHttpClient` for reliable communication with the Docker Engine.
- **`LogWebSocketHandler`**: Uses the `docker-java` streaming API. It attaches a `ResultCallback` to the Docker log stream and forwards frames to the WebSocket session.
- **Resource Management**: The `watchRequests` map tracks active Docker log streams per WebSocket session. When a client disconnects, the Docker stream is explicitly closed to prevent memory leaks and orphaned processes.
- **Non-blocking**: Logs are streamed asynchronously as they are produced by the container.
