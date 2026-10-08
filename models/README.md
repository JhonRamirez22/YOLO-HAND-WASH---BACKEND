# Modelos de inferencia

## Reproducibilidad de métricas

Al 2 de octubre de 2026, el dataset referenciado por
`datasets/handwash_public_7steps.yaml` está disponible localmente, pero sus
carpetas están ignoradas por Git: hay 567 imágenes/etiquetas de train y 140 de
val. La evaluación de val se volvió a ejecutar en esta auditoría con el modelo
activo, en CPU Apple M3, `imgsz=416`, `conf=0.6`, `batch=1`, `plots=False` y
`save_json=False`; los resultados se escribieron en un directorio temporal. La
auditoría posterior con `scripts/audit_yolo_split_duplicates.py` encontró
107/140 imágenes de val con un candidato casi duplicado en train (pHash <=4 y
error medio de píxel <=5/255 tras reducir a 64×64). No hay duplicados binarios
exactos. La inspección visual confirmó dos pares casi idénticos de la misma
clase (`val/0_2.jpg`/`train/0_62.jpg` y `val/3_0.jpg`/`train/3_80.jpg`). Por
tanto, estas cifras solo describen ese split posiblemente contaminado; no
demuestran generalización ni deben tratarse como métrica independiente,
clínica o de Continuity Camera. El manifiesto marca esa evaluación como no apta
para acreditar precisión. Falta el ID de video/escena fuente para rehacer el
split por grupos. La muestra de 90 imágenes derivadas de videos también es
solo diagnóstica y no equivale a evaluación temporal o de sesión.

## Modelo activo de pasos

### Integridad de los artefactos al iniciar

El arranque canónico compara el SHA-256 del detector, el modelo de pose y el
clasificador auxiliar habilitado con una única entrada de
`model-manifest.json` antes de cargar cada peso. Un archivo sin registro o
modificado detiene el capturador. `.venv/bin/python
scripts/validate_handwash_models.py` valida la huella y, después, la tarea y
los nombres de clase del detector activo. Esta huella detecta cambios
accidentales; el manifiesto no está firmado y no demuestra la procedencia del
archivo ni su precisión.

**Alcance clínico:** el peso activo solo detecta siete clases de acciones
definidas por el proyecto; no está verificado que las siete sean movimientos de
fricción. La guía de lavado con agua y jabón de la OMS enumera seis movimientos
de fricción (pasos 2–7); el mapeo uno-a-uno de las siete clases del proyecto a
esos movimientos no se ha validado.
No detecta mojado, presencia/cobertura de jabón, enjuague, secado ni cierre del
grifo. No debe usarse para aprobar el procedimiento completo de la OMS ni para
afirmar ausencia de contaminación. Ver el plan de anotación y aceptación en
[`docs/REQUISITOS_MODELO_OMS.md`](../../docs/REQUISITOS_MODELO_OMS.md).

`handwash_yolo26n_7pasos.pt` es el peso principal que usa el capturador de
Continuity Camera en `scripts/run_yolo26_continuity_camera.py`. El gateway
FastAPI que también lo cargaba fue archivado y no forma parte del runtime. Un checkpoint candidato, como
`handwash_yolo26s_robust.pt`, nunca se selecciona solo por existir: para
probarlo se configura explícitamente `HANDWASH_YOLO_MODEL` después de validar
su huella, tarea y clases. El peso recibido por el usuario
es un detector YOLO26n de siete clases con etiquetas `paso_1`…`paso_7`, que el
capturador normaliza a los nombres canónicos:

```text
Paso1_Palmas, Paso2_Dorsos, Paso3_Interdigitales, Paso4_Nudillos,
Paso5_Pulgar, Paso6_PuntaDeDedos, Paso7_Circulares
```

El runtime traduce los IDs 0–6 según las etiquetas `paso_1`…`paso_7` y el orden
del conjunto de referencia del proyecto. Como faltan el `data.yaml` y la rúbrica
exactos del entrenamiento, esa traducción no demuestra que el checkpoint haya
aprendido los movimientos asignados; su taxonomía sigue sin verificar y el gate
clínico permanece cerrado. El productor convierte cada alias a `PASO_1_*` ...
`PASO_7_*` antes de enviarlo a Java. Este peso no tiene clase `Fondo`; la ausencia
sostenida de una detección usa la ventana de pérdida de evidencia existente para
reiniciar el intento. Otros detectores que sí incluyan `Fondo` mantienen ese
control negativo.

El export histórico de DataSet5 contiene un error confirmado: `movement_code 7`
(`turn off faucet`) quedó etiquetado como `Paso7_Circulares`. El generador ahora
lo asigna a `Fondo` y rechaza producir un dataset de siete pasos porque esa
fuente no contiene anotaciones verificadas para el movimiento circular. El YAML,
las etiquetas y los pesos históricos no se regeneraron ni validaron; los scripts
de entrenamiento los bloquean por carecer del contrato versionado. No se sabe si
el checkpoint activo se entrenó con esos datos: su `data.yaml` no fue entregado.
Por eso `PASO_7` sigue sin semántica verificada y no acredita fricción OMS. El
registro mantiene esta incertidumbre en `model-manifest.json`.

La carpeta recibida `yolo26n_7pasos_b64` contiene `args.yaml`, `results.csv` y
checkpoints, pero su `weights/best.pt` tiene el mismo SHA-256 que el peso activo:
no aporta un modelo distinto y ya está integrado como el detector actual. Sus
métricas reportadas alcanzan mAP50 0,995 y mAP50-95 0,87621 en la época 25; son
del `results.csv` de ese entrenamiento, no una reproducción independiente. El
registro configura 50 épocas y contiene 35. Como no incluye `data.yaml` ni el
dataset, no se pueden verificar el orden/significado de las clases, la división
de validación ni esas métricas. La procedencia queda anotada en
[`model-manifest.json`](model-manifest.json). No se reemplazaron pesos ni se
ejecutó entrenamiento; la inferencia de validación reproducida en esta Mac está
documentada arriba y no verifica el `data.yaml` ni la procedencia del run.

El capturador canónico limita la ruta primaria del detector a 8 FPS y da prioridad
al hilo de localización de manos para no dejarlo esperando al modelo de pasos;
puede añadir una recuperación del recorte a 640 px como máximo cada 0,8 s si
la ruta primaria no acepta una clase. Por tanto, 8 FPS es el límite primario,
no el total de inferencias. El capturador canónico no ejecuta TTA ni multiescala;
esas opciones pertenecen únicamente al gateway FastAPI experimental archivado
y no están disponibles en la estación soportada.

## Candidato OMS completo (todavía no disponible)

El capturador también admite un detector `detect` con las 36 clases exactas
de `docs/REQUISITOS_MODELO_OMS.md`: once acciones, contacto de riesgo y
espuma/no-espuma en doce regiones. El backend Java reinicia el intento ante
fases fuera de orden, cobertura no confirmada, contacto de riesgo o duración
insuficiente. `HANDWASH_OMS_MODEL_READY` permanece desactivado por defecto. El
supervisor solo lo habilita junto con `HANDWASH_OMS_INPUT_ENABLED` tras verificar
un release firmado que incluya el peso OMS y el `data.yaml` exacto dentro del
proyecto con su SHA-256, además de la rúbrica revisada, evaluación independiente
en secuencias completas nunca usadas en entrenamiento,
piloto de cámara y revisión clínica; Java repite la verificación antes del
arranque. `station-demo` nunca habilita OMS. `scripts/train_handwash_who.py`
prepara un candidato sin reemplazar `handwash_yolo26n_7pasos.pt`.

La evaluación reproducida el 2026-10-02 contra las 140 imágenes de val del
dataset público de siete pasos de `dpt-xyz/hand-wash-compliance-yolo` informó
precisión 1.0, recall 1.0, mAP50 0,995 y mAP50-95 0,8644. Debido a la
contaminación sospechada descrita arriba, estos valores se conservan solo como
traza histórica y **no** son una medida defendible de generalización. El tiempo
de inferencia CPU fue 12,3 ms/imagen en Apple M3; una medición histórica del
manifiesto fue 10,75 ms, por lo que no debe asumirse que los tiempos sean
idénticos entre ejecuciones. El mAP50-95 reportado por paso quedó entre 0,791 y
0,957; el proxy de recorte también usó el mismo split y comparte la limitación.

### Muestra diagnóstica de pose y clasificador (2026-10-02)

Se evaluaron determinísticamente 10 imágenes por cada una de nueve clases
(90 total) de `ENTRENAMIENTO/derived_handwash_yolo26_cls_2fps_2026-09-26/test`,
con `scripts/evaluate_derived_detector.py --with-pose --per-class 10
--pose-box-confidence 0.001`. No se entrenó ni se guardó ningún medio. Con el
localizador activo, hubo poses bilaterales en 44/90 imágenes y recortes frescos
en 68/90. La propuesta combinada coincidió con 59/90 etiquetas antes del gate
bilateral y con 40/44 en los frames con dos poses válidas; hubo un paso falso en
frames negativos con evidencia bilateral. El clasificador auxiliar, evaluado
aisladamente, coincidió en 79/90 y tuvo dos propuestas de paso falsas entre las
clases negativas. Su latencia CPU media fue 24 ms en los frames elegibles.

La comparación del localizador candidato, usando la misma muestra y añadiendo
`--pose-model backend/models/yolo26_hand_pose_candidate.pt`, encontró poses
bilaterales en 23/90 y recortes frescos en 57/90. La propuesta combinada
coincidió en 47/90 antes del gate y en 21/23 entre los frames bilaterales; no
hubo pasos falsos en los frames negativos bilaterales de esta muestra. El
candidato no mejoró la cobertura bilateral ni los aciertos condicionales
totales frente al activo, por lo que permanece desactivado. El conjunto pequeño
no demuestra rendimiento en video, temporalidad, sesión completa ni cámara
real; estos conteos tampoco sustituyen los votos y validaciones de Java.

La reproducción pública y ambas muestras están registradas en
`backend/models/model-manifest.json`. Ninguna cifra certifica el procedimiento
OMS completo ni la precisión en vivo del iPhone.

## Modelo auxiliar de manos

`yolo26s-pose-hands.pt` es un modelo de pose de mano con una clase (`hand`) y
21 keypoints en formato `[x, y, confianza]`. El runtime exige esa forma para
que el filtro de visibilidad no intente leer un canal inexistente. Ejecuta la
pasada económica a 320 px y, si localiza menos de dos poses completas, reintenta
el frame a 640 px como máximo cada 0,5 s para recuperar una mano pequeña u
ocluida. Una pose primaria válida sigue habilitando el recorte; si el reintento
no mejora la cantidad de poses, conserva la detección primaria y su timestamp.
El umbral de caja bajó a `0.001` para no descartar manos cuya caja es débil pero
cuyos keypoints siguen siendo utilizables; cada pose aún debe tener 7 keypoints
con confianza `>=0.1`. NMS usa IoU `0.90` y conserva hasta 16 propuestas antes
de deduplicar poses repetidas geométricamente. En la secuencia retenida de 84
frames, estas opciones elevaron las poses bilaterales de 6/84 a 56/84 frente a
la configuración anterior. Las cajas visuales y de recorte mantienen su umbral
independiente `>=0.15`; bajar el score permisivo no dibuja cajas débiles.
Conserva la última caja positiva durante
un máximo de 0,5 s para que un único frame negativo no cambie de inmediato el
recorte; cuando caduca, deja de acreditarla. Las cajas detectadas se muestran
aunque la pose tenga menos de 7 keypoints visibles y disparan la recuperación de
mayor resolución. En `FRICCION_PARCIAL`, una caja con confianza `>=0.15` también
habilita el recorte para pasos aunque falten keypoints: solo enfoca el detector,
no se reporta como pose válida ni como evidencia OMS. OMS exige al menos 7
keypoints visibles. La recuperación a 640 px refresca el frame después de
adquirir el acelerador para no localizar manos que ya se movieron. El recorte con
contexto de una o dos manos entra al
`handwash_yolo26n_7pasos.pt`; el video conserva el plano completo, dibuja las cajas y
añade un inset ampliado del encuadre actual calculado con las cajas de mano más
recientes. Las cajas para recorte conservan el snapshot exacto donde se obtuvieron.
Ese snapshot es la entrada del detector de pasos; el respaldo de cuadro completo
conserva su propia imagen reciente de cámara. Cada frame aporta como máximo una
observación al filtro temporal y nunca se procesa uno anterior al último analizado,
incluso al cambiar entre respaldo y recorte. La edad se mide desde la captura:
una inferencia lenta no rejuvenece una imagen y no acredita pasos cuando supera
`--hand-max-age` (0,5 s por defecto). El borde sobre el plano completo
señala el último ROI que sí recibió el detector. Las cajas del detector de
pasos y ese borde se ocultan tras 0,5 s
sin una inferencia nueva, para no presentarlos como resultados actuales. Todo
es una visualización en memoria, no una grabación.
El modelo de pose no clasifica pasos ni reemplaza el peso activo.

La detección primaria de manos se publica antes de esperar la recuperación a
640 px. Tras terminar cada ciclo de manos se reservan al menos 50 ms para que
el detector de pasos pueda obtener el acelerador, evitando que una localización
lenta lo ocupe continuamente. Pasos reintenta al siguiente frame si no obtiene
el acelerador; solo una inferencia efectivamente iniciada consume su intervalo.
La señal de pérdida de fase depende del tiempo desde la última observación
estable, incluso si se omiten inferencias; al caducar también se vacían los votos.

El video se anota y publica en un hilo independiente de la inferencia de pasos,
con un límite de 20 FPS. Consume el frame de cámara más reciente y un snapshot
coherente del último resultado, cuyos tensores ya están en CPU. Una inferencia
lenta puede reducir la frecuencia de reconocimiento, pero no bloquea el bucle
que publica el video. Las cajas siguen sujetas a su caducidad desde la captura.

El checkpoint de manos proviene del proyecto comunitario
[`vbalab/YOLO26s-pose-hands`](https://github.com/vbalab/YOLO26s-pose-hands),
ajustado sobre Ultralytics Hand Keypoints. El repositorio publica un
`results.csv` de 100 épocas; en la época 100 informa box precision 0.98310,
recall 0.97883, mAP50 0.99233 y mAP50–95 0.92935; para pose informa precision
0.93627, recall 0.90825, mAP50 0.94537 y mAP50–95 0.86034 ([resultados
publicados](https://raw.githubusercontent.com/vbalab/YOLO26s-pose-hands/main/runs/pose/train/results.csv)).
Son cifras del autor sobre el conjunto de validación de keypoints de Ultralytics;
no se han reproducido de forma independiente y no miden el comportamiento con
la cámara de Continuity, el lavamanos, espuma, agua ni oclusiones de esa escena.
Por tanto, respaldan conservar este modelo como localizador base, pero no
garantizan detección en el entorno real. El capturador comprueba la tarea, la
clase y la forma de keypoints al iniciar; la precisión en la instalación real
sigue pendiente de validación visual representativa.

### Alternativa de localización YOLO26s-pose

`yolo26_hand_pose_candidate.pt` es una segunda pose YOLO26s de una clase
(`hand`) y 21 keypoints, descargada del checkpoint de
[`poptoz/yolo26-hand-pose-face-detection`](https://huggingface.co/poptoz/yolo26-hand-pose-face-detection/tree/main/checkpoints).
El autor publica box mAP50 0.993, box mAP50–95 0.912, pose mAP50 0.942 y pose
mAP50–95 0.843 en el conjunto Ultralytics Hand Keypoints. Esos números son los
del autor y no validan el comportamiento con la cámara de Continuidad ni con
manos enjabonadas/ocluídas en el lavamanos.

Queda guardado como alternativa, no predeterminado. Sus métricas publicadas son
ligeramente inferiores a las del localizador activo en el mismo conjunto de
referencia; esto no implica que el modelo activo sea mejor en el lavamanos, pues
ninguno cuenta con evaluación representativa de ese escenario. Para seleccionarlo
explícitamente al iniciar desde la raíz del proyecto:

```bash
HANDWASH_YOLO_HAND_MODEL="$PWD/backend/models/yolo26_hand_pose_candidate.pt" ./scripts/start_handwash.sh
```

El capturador valida tarea, clase y forma de keypoints al iniciar. El checkpoint
conserva SHA-256 `39cb54e63cac0d8905d7cab2112430dc9bf60a26779e361165fc03bf9c6ca36d`.
La ficha no aclara la licencia específica del peso; revisar los términos del
dataset y del modelo antes de redistribuirlo o usarlo comercialmente.

Si la inferencia de pose falla después del arranque, el hilo conserva el video
y reintenta con espera exponencial. El flujo parcial mantiene el detector de
pasos en cuadro completo como respaldo; el protocolo OMS no inventa evidencia
espacial y se abstiene hasta recuperar una pose válida.

Si no hay una caja reciente, el modo de siete pasos recurre al detector activo
sobre el cuadro completo a 640 px (resolución de su entrenamiento), limitado a
6 FPS para compartir el acelerador con la recuperación de manos. Ese respaldo
exige confianza `>=0.75` y tres coincidencias
en una ventana de cinco inferencias; una lectura floja no borra inmediatamente
los votos anteriores. Solo acepta las siete etiquetas canónicas; `Fondo` o una
clase auxiliar nunca se publican como paso. Cuando se pierde una fase estable,
espera 0,9 s antes de enviar `Fondo` al backend y reiniciar el intento, por
debajo de la brecha de detección predeterminada de 1,5 s. El protocolo OMS no
usa este respaldo sin pose porque necesita validar también evidencia espacial
de jabón.

En la misma referencia posiblemente contaminada de 140 imágenes, con `imgsz=320`, score de caja `0.01`
y al menos 7 keypoints con visibilidad `>=0.1`, localizó manos en 139/140. El
detector activo clasificó correctamente las 139 imágenes recortadas. La imagen
restante se resuelve mediante el respaldo de cuadro completo del detector
activo. En ese conjunto pequeño, el detector de pasos acertó 140/140 imágenes
completas; esto no equivale a una validación con vídeo del iPhone ni a una
garantía de precisión clínica.

## Peso recibido en `ENTRENAMIENTO/`

El antiguo `ENTRENAMIENTO/weights/best.pt` y `ENTRENAMIENTO/args.yaml` ya no
están en el directorio entregado; se conservan aquí los resultados de la
auditoría previa. Eran un entrenamiento de **clasificación**, YOLO26n-cls a
224 px. Su validación propia alcanzó
83.385% top-1 (época 16), pero ese porcentaje corresponde a su propio dominio
de cámaras sobre lavamanos y está dominado por la clase mayoritaria `00_otro_movimiento`.
Su matriz normalizada da recalls por clase entre 0.43 y 0.89; los pasos no
tienen rendimiento uniforme. Las clases son `00_otro_movimiento`, seis movimientos
de fricción numerados `01`–`06` y `07_cerrar_grifo_con_toalla`: la última **no**
corresponde a `Paso7_Circulares` del flujo Java.

Para comprobar si ayudaba al visor actual, se evaluaron los seis pasos compatibles
en las 120 imágenes correspondientes de `datasets/handwash_public_7steps/images/val`.
Con fotogramas completos acertó 8/120 (6.7%); con el recorte etiquetado de las
manos, 1/120 (0.8%). Predijo `00_otro_movimiento` en casi todos los casos. La
prueba se podría repetir sin guardar imágenes si se restaura ese peso:

```bash
.venv/bin/python scripts/evaluate_who_classifier.py --weights ENTRENAMIENTO/weights/best.pt
```

Por incompatibilidad de clases y dominio, el peso queda catalogado en
`model-manifest.json` como candidato de investigación, **desactivado** en el
capturador. Activarlo como voto, veto o sustituto del detector haría menos
preciso el sistema en este conjunto. Además, el dataset original de los videos
de `ENTRENAMIENTO/` no se entregó, por lo que la precisión del modelo en la
cámara real del iPhone todavía no está medida. La referencia pública es pequeña
y no sustituye una validación independiente con sesiones completas.

## Clasificador derivado de videos a 2 FPS (respaldo condicionado en vivo)

`backend/models/handwash_who_yolo26m_cls.pt` es la copia byte a byte del
`best.pt` entregado en
`ENTRENAMIENTO/derived_handwash_yolo26_cls_2fps_2026-09-26/runs/who-yolo26m-cls-320/weights/best.pt`.
El runtime y los evaluadores usan la copia en `backend/models/`, porque
`ENTRENAMIENTO/` se excluye de Git; el original se deja intacto. Es un
**clasificador de imagen YOLO26m-cls**, entrada 320 px, con nueve clases;
no detecta cajas ni sustituye `handwash_yolo26n_7pasos.pt`. Incluye seis clases
de fricción compatibles con los pasos 1–6 del proyecto, `00_other_washing_movement`,
`08_not_washing` y `07_turn_off_faucet_with_paper_towel`. Esta última significa
cierre del grifo con toalla, no `Paso7_Circulares`; no se debe mapear como tal.

El resumen registra 2.935 videos fuente, 2.934 episodios con anotación de
consenso, un video sin consenso y un JSON de anotación inválido excluido. Las
imágenes se extrajeron a 2 FPS. Al contrastar `manifest.csv`, no encontré videos
fuente compartidos entre train, val y test (2.348/293/293 videos, respectivamente).
La matriz guardada en `runs/who-yolo26m-cls-320-test/confusion_matrix.png` suma
5.426 aciertos de 6.324 imágenes (85,8% top-1 calculado de esa matriz); recall
por clase va aproximadamente de 74% a 94%, con peor resultado en `00_other_washing_movement`
y `03_palms_fingers_interlaced`. La validación de `results.csv` reporta top-1
máximo 83,653% en época 81; se registran 94 épocas de 100 configuradas.

Se añadió compatibilidad con sus nueve clases al comparador reproducible:

```bash
.venv/bin/python scripts/evaluate_who_classifier.py
```

En 120 imágenes del mismo split posiblemente contaminado de `datasets/handwash_public_7steps/images/val`,
acertó **39/120 (32,5%)** con fotograma completo y **15/120 (12,5%)** con el
recorte etiquetado. El detector de siete pasos actual había acertado 140/140
en ese conjunto pequeño; la referencia no es una prueba independiente ni
representa necesariamente la cámara del iPhone. El clasificador falló muchos
pasos en ese dominio externo y no se ejecuta sobre el recorte. Por eso su uso
se limita al dominio de los videos que lo entrenaron y a frames con evidencia
bilateral fresca; esa restricción no garantiza rendimiento en otros lavamanos.
La matriz de test entregada carece de la configuración/registro necesarios
para reproducirla independientemente.

El runtime usa este checkpoint por defecto en `FRICCION_PARCIAL`. Con dos poses
válidas y frescas del mismo frame, una clase compatible con confianza `>=0.75`
del clasificador tiene prioridad sobre el detector; sus tres clases negativas
abstienen y pueden vetar una clase de fricción contradictoria. Si el clasificador
no está seguro, se conserva el candidato del detector. Java sigue siendo la
autoridad de la intención inicial, evidencia bilateral, secuencia, duración e
infracciones; ambos modelos solo proponen fases. El clasificador procesa el
fotograma completo a 320 px en CPU; dos ejecuciones del mismo replay midieron
23,3–26,0 ms por frame elegible. Puedes apagarlo explícitamente con
`HANDWASH_CLASSIFIER_FALLBACK=0`; el flujo normal lo habilita.

En una muestra determinista independiente del split `test/` (20 imágenes por
cada una de las nueve clases, 180 en total), el sistema tuvo poses bilaterales
en **92/180 (51,1%)** imágenes. Dentro de ese subconjunto bilateral la
propuesta combinada coincidió en **82/92 (89,1%)** y produjo **1 paso falso**
en negativas. Ese 89,1% es condicional a que la pose encuentre dos manos; no es
precisión global, cobertura de sesión ni validación con el iPhone. Antes de
aplicar la compuerta bilateral, la propuesta combinada coincidió en 117/180
(65,0%) y propuso pasos falsos en 8 imágenes negativas. El clasificador aislado
coincidió en 156/180 (86,7%) y propuso pasos en 2 negativas. La muestra es
pequeña y no sustituye evaluación por sesiones ni predice exactamente el
dominio del iPhone. Reproducir solo con inferencia:

```bash
.venv/bin/python scripts/evaluate_derived_detector.py --with-pose --per-class 20 --pose-box-confidence 0.001
```

Reejecutado el **2026-10-02** con los pesos activos y el mismo comando, reprodujo
82/92 decisiones correctas tras la compuerta bilateral; la inferencia del
clasificador promedió 23,3 ms en CPU sobre 92 frames elegibles (otra ejecución
del mismo día midió 26,0 ms). Acuerdo por clase fuente, contado solo cuando el
localizador detectó dos manos válidas:

| Clase fuente | Decisiones correctas / poses bilaterales |
|---|---:|
| Otro movimiento de lavado (negativa) | 10/11 |
| Palmas | 12/13 |
| Palma sobre dorso | 9/9 |
| Palmas con dedos entrelazados | 9/13 |
| Nudillos entrelazados | 9/9 |
| Frotado rotacional del pulgar | 10/13 |
| Puntas de dedos en palma | 12/13 |
| Cierre del grifo con toalla (negativa) | 8/8 |
| No lavado (negativa) | 3/3 |

La debilidad observada en esta muestra está en dedos entrelazados y pulgar; no
es evidencia suficiente para retocar umbrales sin medir el impacto en secuencias
completas. El reporte es una selección determinista de frames, no 180 sesiones
independientes ni validación clínica. La salida se imprimió en terminal; no se
entrenó el modelo ni se escribieron imágenes o videos.

En la secuencia retenida de 84 frames, la configuración de pose a `0.001`
encontró dos poses en 56/84 frames, frente a 6/84 con `0.005`. Los pasos
correctos 1–6 después del filtro temporal y la evidencia bilateral subieron de
0/74 a **26/74**. No hubo ninguna medida válida de movimiento de inicio ni
inicio confirmado en ese clip; por tanto, todavía no valida la intención inicial
ni la sesión Java completa. Esta repetición no guardó frames ni entrenó modelos:

El FPS original de ese video no está incluido en el proyecto. El manifiesto de
extracción declara muestreo de 2 FPS y stride de 15, pero eso no sustituye una
lectura del medio original con `ffprobe`. No ejecutes el replay temporal hasta
obtener ese MP4 y pasar su FPS explícitamente:

```bash
ffprobe -v error -select_streams v:0 -show_entries stream=avg_frame_rate,r_frame_rate \
  -of default=noprint_wrappers=1 /ruta/al/video-original.mp4
.venv/bin/python scripts/evaluate_derived_sequence.py --source-fps "$FPS_CONFIRMADO"
```

El dataset del clasificador distingue seis fricciones, otros movimientos,
`08_not_washing` y `07_turn_off_faucet_with_paper_towel`. El cierre del grifo es
negativo para las fases de fricción y **nunca** se asigna a `Paso7_Circulares`.
El peso activo de siete clases todavía necesita una validación representativa
de su clase 7. Los filtros temporales y el Stepper Java continúan activos; un
frame no adelanta por sí solo la secuencia. Los resultados anteriores con
umbral `0.005` y 10 imágenes por clase se conservan en
`backend/models/model-manifest.json` como historial, no como configuración actual.

En una comparación histórica con umbral de caja `0.005`, el localizador
alternativo `backend/models/yolo26_hand_pose_candidate.pt` obtuvo pares de
poses en 3/84 frames frente a 6/84 del localizador principal de entonces,
aunque alcanzó la visibilidad estricta inicial en 3/84 frente a 1/84. Esa
comparación antecede el nuevo umbral `0.001`; no se ha repetido con los
parámetros actuales. Ninguno produjo movimiento bilateral válido ni evidencia
de paso aceptable tras los filtros en aquella comparación. El candidato queda
optativo y requiere prueba representativa con la cámara real.

También se comparó en el mismo muestreo de 180 imágenes de prueba usado para el
localizador activo, con umbral `0.001`, inferencia CPU y sin guardar medios. El
candidato encontró dos poses en **50/180 (27,8%)**, frente a **92/180 (51,1%)**
del modelo activo. Su acuerdo fue **45/50** cuando había dos poses, con cero
falsos pasos en negativas bilaterales; antes de esa compuerta tuvo 95/180 de
acuerdo y seis propuestas falsas en negativas. El aumento condicional no
compensa la pérdida de 42 frames con evidencia bilateral, así que el candidato
no se activa. Esta comparación diagnóstica no valida sesiones ni el dominio de
Continuity Camera:

```bash
.venv/bin/python scripts/evaluate_derived_detector.py --with-pose --per-class 20 --pose-box-confidence 0.001 --pose-model backend/models/yolo26_hand_pose_candidate.pt
```

Con el umbral anterior `0.005`, también se probaron el localizador actual a
640 px sin pasada de recuperación y un recorte fijo normalizado
`[0.15, 0.04, 0.85, 0.70]` en ese mismo video.
Lograron pares de poses en 2/84 y 1/84 frames, respectivamente, y ambos dejaron
0 pasos tras los filtros temporal y bilateral. Ni subir resolución ni fijar ese
recorte resolvió esta vista; no se endurece el costo de inferencia ni se fija
una zona a partir de un solo video.

## Dataset Roboflow `Hand-Hygiene.v1i.yolo26` (transferencia aislada)

El export local de Roboflow ([versión 1, licencia CC BY 4.0](https://universe.roboflow.com/jhon-ramirezpo-campusucc-edu-co/hand-hygiene-ajdob/dataset/1)) contiene 3.358 imágenes (2.943 train, 125 valid,
290 test) y **una sola etiqueta**, `Performing-Hand-Hygiene`. Sus cajas son
útiles para adaptar características visuales al contexto de lavado, pero no
indican cuál movimiento está ocurriendo ni distinguen manos quietas de intención
de lavado. No se deben traducir directamente a una clase de paso ni enviarse a
Java como evidencia de intención.

Para aprovecharlo sin alterar el detector usado por el dashboard se añadió
`scripts/train_hand_hygiene_transfer.py`. Entrena en dos etapas y escribe ambos
pesos en una carpeta fechada bajo `runs/detect/handwash-hygiene-transfer/`:

1. Ajusta `yolo26n.pt` a la única clase genérica de Roboflow.
2. Transfiere ese checkpoint al detector de siete pasos con las etiquetas
   canónicas de `datasets/handwash_public_7steps.yaml`. La cabeza se adapta de
   una a siete clases; el resultado final vuelve a producir `Paso1_Palmas` ...
   `Paso7_Circulares`, que el backend Java procesa con sus patrones State y
   Strategy existentes.

Primero valida rutas, etiquetas y compatibilidad, sin crear archivos ni entrenar:

```bash
.venv/bin/python scripts/train_hand_hygiene_transfer.py --check-only
```

Para entrenar el candidato aislado:

```bash
.venv/bin/python scripts/train_hand_hygiene_transfer.py
```

El script no copia ni reemplaza `backend/models/handwash_yolo26n_7pasos.pt` y rechaza
si las clases finales no coinciden con las que espera el capturador. El detector
de Roboflow y el peso final son experimentales hasta medirlos en sesiones nuevas
con la cámara real; el conjunto local de siete pasos es pequeño y su split de
validación no equivale a vídeos independientes. Una vez finalizado, el candidato
se puede seleccionar temporalmente con `HANDWASH_YOLO_MODEL="/ruta/al/best.pt" ./scripts/start_handwash.sh`;
para volver al anterior basta iniciar sin esa variable. Java permanece como
autoridad de orden, duración, intención y reinicio, y el dashboard no declara
certificación OMS completa.

En esta Mac PyTorch no detecta aceleración MPS ni CUDA, por lo que `auto` elegirá
CPU. El preflight sí puede ejecutarse de inmediato; el entrenamiento completo
puede tardar y aumentar el uso de CPU, así que no se inicia en segundo plano ni
se activa por sí solo. Si se elige CPU, el script limita PyTorch a 3 hilos por
defecto (`--cpu-threads 2` puede reducir más el uso, a costa de tardar más).

## Clasificador externo (experimento archivado)

`handwash_stage_classifier.pt` proviene del repositorio público
[`Vishwakarma-Atul/handWash`](https://github.com/Vishwakarma-Atul/handWash). Es
un clasificador YOLO de 13 clases: los siete movimientos separados en izquierda
y derecha, más `background`. No es un detector de cajas y por eso no reemplaza
el modelo activo. Su integración existía solo en el gateway FastAPI experimental,
ahora archivado en `archive/experimental_fastapi_yolo/`; no es una opción del
capturador canónico.

Antes de activarlo se comparó con el conjunto local de validación; obtuvo solo
3.57% de acierto por paso en ese dominio, así que permanece desactivado por
defecto. Esto evita introducir falsos positivos. El peso sí queda disponible
para probarlo con imágenes del mismo entorno para el que fue entrenado.

## Clasificador multivista MFH (experimento archivado)

El entrenamiento `scripts/train_handwash_mfh.py` genera
`handwash_multiview.pt` a partir del dataset MFH. Es un clasificador de imagen
de siete clases, no un detector de cajas. Su integración corresponde al gateway
experimental archivado y no funciona en el capturador actual.

No se activa hasta que exista el archivo y se valide su `top1` con escenas no
usadas durante el entrenamiento. Así se evita que una clasificación externa
reemplace una detección confiable.

## Pesos que no deben usarse como detector de lavado

- `yolo26n.pt`: peso base oficial genérico; no contiene clases de lavado.
- `yolo26n-obb.pt`: detector OBB entrenado con objetos aéreos DOTA, no con
  manos. OBB es el tipo de cabeza geométrica, no un entrenamiento de manos.
- Un peso externo solo se puede usar si es tarea `detect` y contiene las siete
  etiquetas `Paso1_Palmas` ... `Paso7_Circulares`, los alias secuenciales
  `paso_1` ... `paso_7`, o todas las 36 etiquetas OMS.
  El visor lo valida al arrancar y falla si el mapa de clases está incompleto.

## Exportación opcional a ONNX

```bash
.venv/bin/yolo export \
  model=backend/models/handwash_yolo26n_7pasos.pt \
  format=onnx imgsz=640 simplify=False
```

El backend Java puede usar `step_classifier.onnx` cuando se desee inferencia
nativa. El visor macOS usa el `.pt` directamente para aprovechar MPS/Metal.
