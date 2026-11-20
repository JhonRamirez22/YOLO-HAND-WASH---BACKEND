# Hand Wash Compliance System

Sistema de verificación de lavado de manos OMS con backend Java (Spring Boot), monitor React, app iOS y una ruta FastAPI conservada como prototipo auxiliar.

## Runtime canónico (actual)

- **Backend principal:** `backend/` Spring Boot
- **Puerto por defecto:** `8080` (configurable con `SERVER_PORT`)
- **API/WS canónicos:**
  - `POST /api/session`
  - `GET /api/session/{sessionId}`
  - `DELETE /api/session/{sessionId}`
  - `GET /api/protocols`
  - `POST /api/infer`
  - `WS /ws/{sessionId}`
- **Backend FastAPI existente:** `backend/main.py` (no eliminado, ruta alternativa/prototipo)

## Configuración backend Java

Archivo: `backend/src/main/resources/application.yml`

Variables clave:

- `SERVER_PORT` (default `8080`)
- `HANDWASH_HAND_DETECTOR_PATH` (default `models/hand_detector.onnx`)
- `HANDWASH_STEP_CLASSIFIER_PATH` (default `models/step_classifier.onnx`)
- `HANDWASH_RECEPTOR_CONFIDENCE_THRESHOLD` (default `0.6`)
- `HANDWASH_HAND_CONFIDENCE_THRESHOLD` (default `0.25`)
- `HANDWASH_STEP_CONFIDENCE_THRESHOLD` (default `0.15`)

> Si faltan ONNX, el backend **no cae**: inicia API/sesiones/WebSocket y `POST /api/infer` responde estado de modelo no disponible de forma controlada.

## Ejecución

### Backend Java

```bash
cd backend
mvn spring-boot:run
```

### Frontend React

`frontend/.env.example` ya apunta al backend Java por defecto:

```env
VITE_API_URL=http://localhost:8080
VITE_WS_URL=ws://localhost:8080
```

```bash
cd frontend
npm install
npm run dev
```

### iOS

- Configuración de backend ahora es configurable por `Info.plist` opcional (`BackendHost`, `BackendPort`) con fallback a `localhost:8080`.
- Mantiene integración CoreML/UltralyticsYOLO.
- `best.mlpackage` debe generarse/agregarse externamente (no incluido en repo).

## Contratos de compatibilidad

- Protocolo clínico canónico: `CLINICO_QUIRURGICO` (se acepta `CLINICO` como alias de entrada por compatibilidad).
- Se mantiene `DOMESTICO`.
- Dataset/clases soportan 8 clases (`7 pasos + Fondo`).
- `Fondo` no avanza pasos ni genera infracción falsa en la máquina de estados.
- Mensajes WS mantienen `EstadoLavadoResponse` y agregan `messageType`/`estadoSesion` para adaptación React/iOS.

## Modelo / entrenamiento

- No se fabrican artefactos ONNX/CoreML en este repositorio.
- Scripts de dataset/training se mantienen y se hicieron ajustes de portabilidad:
  - `scripts/prepare_dataset5.py` usa raíz configurable (`HANDWASH_PROJECT_ROOT`) en lugar de ruta fija de máquina.
  - split train/val determinístico (hash estable).
  - `DataSet5_YOLO/dataset5_combined.yaml` usa `path: .`.

## Circuito (futuro)

La carpeta `circuito/` se conserva intacta como integración futura ESP32/BLE. Puede acoplarse luego al flujo iOS para activar/pausar captura por eventos BLE sin mezclar la clasificación YOLO actual.

## Nota sobre FastAPI

`backend/main.py` y el stack Python se conservan sin borrado como camino auxiliar/prototipo. El contrato canónico para integración actual es el backend Java.
