# YOLO Hand Wash Backend

Backend Java que coordina sesiones de lavado de manos, valida las detecciones que recibe del productor YOLO y publica el estado al dashboard. El productor de cámara Python y el frontend se mantienen fuera de este repositorio.

## Responsabilidades

- API REST para crear y vincular sesiones, autenticar productor/dashboard y recibir detecciones.
- Máquina de estados y estrategias para evaluar la secuencia de lavado.
- WebSocket de solo salida para enviar actualizaciones al dashboard.
- Persistencia JDBC con H2 de resúmenes acotados de intentos fallidos definidos en `src/main/resources/schema.sql`; las sesiones activas viven en memoria.
- Inferencia ONNX opcional desde Java; el flujo previsto recibe detecciones del productor Python.

## Stack

| Componente | Versión/configuración |
|---|---|
| Java | 17 |
| Spring Boot | 4.1.1 |
| Base de datos | H2 + JDBC |
| ONNX Runtime | 1.20.0 |
| OpenCV | 4.14.0 mediante JavaCPP 1.5.14 |
| Construcción | Maven |

## Estructura

- `agent/`, `observer/`: recepción y distribución de detecciones.
- `api/v1/`, `controller/`: endpoints, DTO y mappers.
- `intention/`, `state/`, `strategy/`: intención, progreso por pasos y reglas de evaluación.
- `model/`: modelos de dominio y respuestas.
- `security/`: credenciales de sesión, emparejamiento y tickets WebSocket.
- `service/`, `repository/`: lógica de aplicación y persistencia.
- `config/`, `decorator/`, `websocket/`: configuración e integración.
- `models/`: artefactos de modelos; consulta [`models/README.md`](models/README.md) para su inventario y estado.

## Requisitos y ejecución local

Se requiere JDK 17 o superior y Maven. Desde la raíz de este repositorio:

```bash
mkdir -p .runtime
mvn spring-boot:run
```

Por defecto, el servidor escucha en `http://127.0.0.1:8080` y guarda la base H2 en `.runtime/handwash`. El esquema se inicializa desde `schema.sql` al arrancar.

Perfiles disponibles:

```bash
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
SPRING_PROFILES_ACTIVE=station-demo mvn spring-boot:run
```

`station-demo` es una demostración no clínica y exige protocolo de productor v2. El perfil `station` falla de forma segura si no puede verificar el manifiesto firmado y la clave pública externa configurada en `HANDWASH_RELEASE_PUBLIC_KEY_PATH`; úsalo únicamente con artefactos de release aprobados.

Construcción y pruebas:

```bash
mvn test
mvn clean package
java -jar target/hand-wash-compliance-1.0.0.jar
```

## API principal

| Método | Ruta | Uso |
|---|---|---|
| `POST` | `/api/v1/session` | Crear una sesión y obtener sus credenciales/código de emparejamiento. |
| `POST` | `/api/v1/auth/login` | Canjear el código por credencial del productor (`DEVICE`). |
| `POST` | `/api/v1/auth/dashboard-login` | Canjear el código por credencial de lectura (`VIEWER`). |
| `POST` | `/api/v1/deteccion` | Enviar una detección validada para la sesión. Acepta `Authorization: Bearer …`. |
| `POST` | `/api/v1/session/{sessionId}/websocket-ticket` | Solicitar un ticket de un solo uso para WebSocket. |
| `GET` | `/api/v1/deployment/status` | Consultar el modo activo del backend. |
| `GET` | `/actuator/health` | Consultar salud básica. |

El dashboard se conecta a `ws://127.0.0.1:8080/ws/{sessionId}?ticket={ticket}`. El WebSocket solo envía actualizaciones; las detecciones se envían por REST.

## Configuración relevante

| Variable | Valor por defecto | Descripción |
|---|---|---|
| `SERVER_ADDRESS` | `127.0.0.1` | Dirección de escucha. |
| `SERVER_PORT` | `8080` | Puerto HTTP. |
| `HANDWASH_DB_URL` | `jdbc:h2:file:./.runtime/handwash;DB_CLOSE_ON_EXIT=FALSE` | URL JDBC de H2. |
| `HANDWASH_DB_USERNAME` | `sa` | Usuario H2. |
| `HANDWASH_DB_PASSWORD` | vacío | Contraseña H2. |
| `HANDWASH_CORS_ALLOWED_ORIGINS` | `http://127.0.0.1:5173,http://localhost:5173` | Orígenes permitidos. |
| `HANDWASH_INFERENCE_API_ENABLED` | `false` | Habilita la ruta opcional de inferencia ONNX. |
| `HANDWASH_HAND_DETECTOR_PATH` | `models/hand_detector.onnx` | Ruta del detector ONNX. |
| `HANDWASH_STEP_CLASSIFIER_PATH` | `models/step_classifier.onnx` | Ruta del clasificador ONNX. |

Los tokens de sesión son credenciales temporales del proceso y no se deben guardar en Git. La entrada OMS y la ruta de inferencia están deshabilitadas por defecto. `requirements.txt` es una referencia de compatibilidad al runtime de cámara del repositorio padre; no instala dependencias del backend Java.
