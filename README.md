# jsync Drive para Android

Tu Drive de jsync como almacenamiento del teléfono, montado igual que `jsync mount drive`: mismo hub NATS (nodo *leaf*
embebido), mismo handshake firmado con X3DH + Double Ratchet, mismas carpetas cifradas de extremo a extremo.

## Qué hace

| Función | Cómo |
|---|---|
| **Drive como almacenamiento** | `DocumentsProvider` (SAF): aparece en "Archivos" y en el selector de cualquier app. Lectura por rangos bajo demanda (como los marcadores de CfAPI), crear / escribir / renombrar / mover / borrar (el borrado va a la papelera del Drive), búsqueda, y los cambios remotos refrescan la carpeta abierta. |
| **App para explorar y subir** | Compose: explorar, subir archivos, crear carpeta, renombrar, borrar, abrir con otra app, desbloquear carpetas cifradas. Destino de "Compartir" (al Drive o a otro dispositivo). |
| **Carpetas cifradas** | Se muestran bloqueadas hasta introducir la contraseña de la vault (se verifica en el teléfono; nunca se envía). Leer y escribir dentro cuando están abiertas; se pueden volver a bloquear solas (30 min / 4 h / nunca). |
| **Enviar a otros dispositivos** | WebRTC directo entre tus dispositivos (mismo protocolo que la web, `src/lib/p2p.ts`): notificación con Aceptar/Rechazar, dispositivos de confianza, progreso y cancelación; lo recibido va a `Descargas/jsync`. |
| **Carpeta sincronizada** | Una carpeta del teléfono (elegida con el selector del sistema) igual a una carpeta del Drive, en los dos sentidos. Si ambos lados cambian el mismo archivo se conservan las dos versiones; un lado vacío o ilegible no borra el otro (límite del 50 %). |

## Cómo está hecho

```
app Kotlin (Compose, DocumentsProvider, servicio en primer plano)
        │  HTTP en 127.0.0.1 + token Bearer, eventos por SSE
        ▼
libjsyncnode.so  = nodo Go estático (sin cgo ni gomobile; el SDK no tiene NDK), proceso hijo de la app
        ├─ nodo NATS leaf embebido ──▶ hub (jsyncd)
        ├─ mountfs.Client + VFS (árbol, caché de bloques, escritura por staging, cifradas)
        └─ P2P (pion/webrtc) + señalización por el WebSocket de la API del Drive
```

El código Go vive en el repo `../filesharer`: `internal/androidnode` (nodo, API local, P2P), `cmd/jsyncnode` (ejecutable),
`internal/mountfs/vfs.go` (el "sistema de archivos sin kernel" que usa la API). Aquí solo está la app.

## Compilar

```powershell
.\build-node.ps1                 # compila el nodo Go (arm64-v8a y x86_64) a app/src/main/jniLibs
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
.\gradlew.bat assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
.\gradlew.bat testDebugUnitTest  # decisiones de la sincronización (SyncPlanTest)
```

AGP 8.7.3 · Kotlin 2.1.0 · Gradle 8.11.1 · compileSdk 35 · minSdk 26.

## Pruebas

- Go (en `../filesharer`): `go test ./internal/androidnode` — hub y API del Drive reales en proceso: login, montaje, lectura/escritura,
  eventos, carpeta cifrada, P2P con WebRTC real. Ejecutar con `TMP`/`TEMP`/`TMPDIR` apuntando a `filesharer/tmp`.
- Interop con la web real: `../jsync-drive/.test/android-interop-2026-10-19/`.

## Límites conocidos / sin verificar

- **arm64 real**: solo se probó x86_64 (emulador). El nodo arm64 compila, pero no corrió (el emulador lo traduce y falla); hay que probarlo en un teléfono.
- **Servidor**: el cambio de `internal/driveapi/server.go` (tokens de dispositivo en `/api/v1/p2p/devices` y `/ice`) debe estar desplegado en
  `jsyncd` para que el teléfono pueda registrarse y listar dispositivos; sin él, "Devices" muestra un error 403.
- **P2P**: sin servidor TURN solo funciona en la misma red (o con IPs alcanzables); no hay Web Push (un teléfono sin Google Services) — el aviso
  llega mientras el servicio en primer plano mantiene el socket abierto. Candidatos mDNS entre un teléfono real y un navegador: sin probar.
- **Carpeta sincronizada**: no hay vigilancia fiable de cambios locales en SAF; se detectan al abrir la app, con "Sync now", por el
  `ContentObserver` cuando el proveedor lo notifica, y cada 5 min con el servicio activo. No se propagan carpetas vacías ni su borrado.
- Archivos cifrados mayores de 256 MB y Drive con cifrado en reposo (`storage key`): no soportados en el nodo Android.
- Sin vigilancia de batería / datos móviles: sincroniza con la red que haya.
