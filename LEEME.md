# Radio Reloj

Eliges una emisora en el **Amazfit Bip 6** y suena en el **móvil Android** (por su altavoz o por los auriculares que tenga conectados).

```
 Reloj Bip 6 ──Bluetooth──▶ App Zepp (servicio complementario)
                                   │  HTTP solo dentro del móvil (127.0.0.1:8765)
                                   ▼
                            App Android "Radio Reloj" ──internet──▶ emisora
                                   │
                                   ▼
                         altavoz / auriculares del móvil
```

Son dos piezas:

| Carpeta | Qué es | Dónde se instala |
|---|---|---|
| `android-app/` | App Android que reproduce la radio y recibe las órdenes del reloj | En el móvil |
| `zepp-app/` | Mini app del reloj (lista de emisoras y mandos) + servicio complementario que corre dentro de la app Zepp | En el reloj, a través de Zepp |

---

## 1. App del móvil

Hay dos formas de obtener el APK. Las dos producen el mismo archivo, firmado con la misma clave (`android-app/app/debug.keystore`), así que las versiones nuevas se instalan encima de las antiguas.

### Opción A: sin instalar nada, compilando en GitHub (recomendada)

1. Crea una cuenta gratuita en github.com y un repositorio nuevo (privado o público).
2. Sube **todo el contenido** de la carpeta `radio-reloj` (incluida la carpeta oculta `.github`).
3. GitHub compila solo en unos 5 minutos (pestaña *Actions*). Cuando termine, en *Releases* aparece **RadioReloj.apk**.
4. Abre esa página en el móvil (con tu sesión de GitHub iniciada si el repositorio es privado), descarga el APK e instálalo. Android te pedirá permitir "instalar apps de origen desconocido" para el navegador: acéptalo para esta instalación.

### Opción B: con Android Studio

1. Instala **Android Studio** (developer.android.com/studio).
2. *File › Open* y elige la carpeta `android-app`. La primera vez tarda: descarga Gradle, el SDK de Android 36 y las librerías. Si propone actualizar el "Android Gradle Plugin", puedes aceptar.
3. En el móvil, activa la **depuración USB**: *Ajustes › Información del teléfono* › toca 7 veces *Número de compilación*; luego *Opciones de desarrollador › Depuración USB*.
4. Conecta el móvil por USB y pulsa ▶ **Run**. (Alternativa: *Build › Build APK(s)*, copia el `.apk` al móvil e instálalo.)
5. Abre **Radio Reloj** en el móvil:
   - Acepta las **notificaciones**.
   - Si aparece el aviso amarillo de batería, tócalo y elige *Permitir* / *Sin restricciones*.
   - Toca una emisora: debe sonar en el móvil. Arriba tiene que poner *"Conexión con el reloj: lista"*.

La app deja una notificación fija ("Parada · esperando órdenes del reloj"): es lo que la mantiene viva para que el reloj pueda encenderla en cualquier momento. Desde esa notificación también puedes parar o cambiar de emisora.

## 2. App del reloj (Zeus CLI)

1. Instala **Node.js LTS** (nodejs.org) en el PC.
2. En una terminal (PowerShell):
   ```
   npm i -g @zeppos/zeus-cli
   ```
3. Crea una cuenta en la **plataforma de desarrolladores de Zepp** (Zepp Open Platform) y crea allí una *Mini Program* para obtener su **appId** (un número). Ponlo en `zepp-app/app.json`, en `"appId"` (ahora pone `20001`, que es el de ejemplo).
   - Ojo: para `zeus login` la cuenta debe estar registrada con email y contraseña, no con "entrar con Google/Xiaomi".
4. En la carpeta `zepp-app`:
   ```
   npm install
   zeus preview
   ```
   Si te pide iniciar sesión, hazlo. Elige **Amazfit Bip 6** y saldrá un código QR en la terminal.
5. En la app **Zepp** del móvil activa el modo desarrollador: *Perfil › Ajustes › Acerca de* y toca 7 veces el logo de Zepp. Aparece *Perfil › Ajustes › Modo desarrollador*.
6. Ahí usa **Escanear** y apunta al QR. La app se instala en el reloj como **Radio Reloj**.

## 3. Uso

- En el reloj abre **Radio Reloj**: verás la lista de emisoras (la toma del móvil y la guarda para que aparezca al instante la próxima vez).
- Toca una emisora → pasa a la pantalla de reproducción:
  - Fila de arriba: **-5m**, **-30s**, **+30s** (moverse en el tiempo) y **Dir.** (volver al directo).
  - Fila del medio: **<** emisora anterior, **Pausa / Seguir**, **>** emisora siguiente.
  - Fila de abajo: **Vol -**, **Apagar**, **Vol +** (volumen del móvil).
  - Debajo del nombre se ve el estado: *En directo*, *Diferido -12:30* (vas 12 minutos y medio por detrás) o *En pausa -3:05*. Si la emisora lo envía, también la canción.
- **Pausa y retroceso (hasta 2 horas):** mientras una emisora está puesta, la app la va grabando en el móvil. *Pausa* para el sonido pero no la grabación: al darle a *Seguir* continúa justo donde lo dejaste. Se puede retroceder hasta 2 horas, pero solo desde el momento en que pusiste esa emisora. Al cambiar de emisora o pulsar *Apagar* se borra lo grabado. Si se deja en pausa más de 2 horas, se apaga sola.
- La barra de arriba de la lista ("Sonando: …" / "En pausa: …") lleva a la pantalla de reproducción.
- El **control de música normal del reloj** también ve la emisora: puedes pausar, saltar de emisora y cambiar el volumen desde ahí.
- Para cambiar la lista: en la app del móvil, *mantén pulsada* una emisora (editar, subir, bajar, eliminar) o usa *Opciones › Añadir emisora*. El reloj recoge los cambios al volver a abrir Radio Reloj.

## 4. Si algo falla

| Lo que ves | Qué hacer |
|---|---|
| El reloj dice **"Abre Radio Reloj en el móvil"** | Abre la app en el móvil. Si la app está abierta, pone "Conexión con el reloj: lista" y el reloj sigue diciendo esto, la app Zepp está bloqueando las conexiones locales: avísame y lo cambiamos por otra vía. |
| **"Reloj sin conexión con el móvil"** | El reloj no está conectado por Bluetooth o la app Zepp está cerrada en el móvil. |
| Una emisora se queda en "Conectando…" o da error | Su dirección de streaming ha cambiado. Mantén pulsada la emisora en el móvil › *Editar* y pon la nueva URL (puedes poner varias, una por línea; se prueban en orden). |
| Funciona con el móvil desbloqueado pero no con la pantalla apagada | Quita las restricciones de batería a **Radio Reloj** y a **Zepp**. En Xiaomi, Samsung, Huawei, etc. revisa también "inicio automático" / "apps que nunca se suspenden". |
| Después de reiniciar el móvil no responde | Abre Radio Reloj una vez; Android no deja que se active sola al arrancar. |

## 5. Detalles técnicos

- **Emisoras de fábrica** (23, en `android-app/app/src/main/assets/stations.json`): SER, COPE, Onda Cero, RNE (nacional y Madrid), esRadio, Radio Marca, Radio 5, Onda Madrid, IB3 Ràdio, LOS40, LOS40 Classic, Cadena 100, Cadena Dial, Rock FM, Europa FM, Kiss FM, MegaStar, Melodía FM, Hit FM, Radiolé, Radio 3 y Radio Clásica. Direcciones tomadas de la lista pública de TDTChannels (github.com/LaQuay/TDTChannels) en noviembre de 2026; *Opciones › Restaurar la lista de fábrica* vuelve a ellas.
- **Reproductor:** Media3 ExoPlayer. Si una URL falla prueba la siguiente de la emisora.
- **Grabación para pausa y retroceso:** la emisora se graba en trozos (de 3 s en emisoras MP3/AAC; en las HLS se copian sus propios trozos) en la memoria interna de la app y se reproduce desde ahí como una lista HLS local. Se guardan como máximo 2 horas (unos 60 MB por hora a 128 kbps) y se borran al cambiar de emisora o apagar. Si se corta internet, la grabación se reconecta sola. Una emisora en un formato que no se puede grabar (por ejemplo Ogg, o HLS cifrado) suena en directo, sin pausa ni retroceso.
- **Órdenes del reloj:** la app Android abre un pequeño servidor HTTP solo en `127.0.0.1:8765` (no accesible desde la red Wi-Fi ni desde internet). Rutas: `/api/ping`, `/api/stations`, `/api/status`, `/api/play?id=…`, `/api/pause`, `/api/resume`, `/api/toggle`, `/api/stop`, `/api/seek?by=-30` (segundos), `/api/live`, `/api/next`, `/api/prev`, `/api/volume?dir=up|down`. Todas devuelven JSON. La grabación se sirve en `/ts/…` para el propio reproductor.
- **Versiones:** Android 8.0 o superior (minSdk 26, target 36), Android Gradle Plugin 8.13, Gradle 8.14.3, Java 17, Media3 1.8.0. Reloj: Zepp OS con API_LEVEL 4.0 o superior, pantalla cuadrada (390 px de diseño), librería ZML.
