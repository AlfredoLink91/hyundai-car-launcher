# Hyundai — launcher para la tablet del carro

Un launcher de Android hecho a la medida para convertir una tablet vieja en la pantalla del
**Hyundai Accent 2017** (sedán, rojo). Reemplaza la pantalla de inicio de la tablet con un tablero
estilo vidrio: auto en 3D, velocímetro GPS, clima, música, navegación en pantalla dividida con Waze
y encendido/apagado automático con la corriente del carro.

> Proyecto personal, sin fines comerciales y sin relación con Hyundai Motor Company.
> "Hyundai" y su logo son marcas de su respectivo dueño.

![Pantalla de inicio](docs/screenshots/01-inicio.png)

## Capturas

Todas tomadas en la tablet real (1280 × 800). En el modo manejo, el mapa de Waze está difuminado
a propósito para no mostrar ubicaciones privadas.

### Pantalla de inicio

<table>
<tr>
<td width="50%"><img src="docs/screenshots/01-inicio.png" alt="Pantalla de inicio"><br>
<b>Inicio:</b> saludo según la hora, clima con sensación térmica, Bluetooth, Wi-Fi y batería,
auto en 3D, brillo y volumen, velocímetro GPS y música con portada.</td>
<td width="50%"><img src="docs/screenshots/02-auto-360.png" alt="Auto girado con el dedo"><br>
<b>Auto 360°:</b> el Accent se gira con el dedo, con inercia, en 36 ángulos pre-renderizados.</td>
</tr>
<tr>
<td><img src="docs/screenshots/03-apps.png" alt="Cajón de aplicaciones"><br>
<b>Apps:</b> solo las aplicaciones útiles en el carro, con el crédito del modelo 3D abajo.</td>
<td><img src="docs/screenshots/04-menu-apagado.png" alt="Menú de apagado"><br>
<b>Botón de encendido:</b> apagar pantalla, reiniciar o apagar la tablet.</td>
</tr>
</table>

### Modo manejo (pantalla dividida con Waze)

![Modo manejo](docs/screenshots/05-manejo.png)

Waze en ⅓ de la pantalla y el tablero en el resto: el auto de 3/4 trasero sobre una carretera
alineada con su dirección (las líneas avanzan y las llantas giran con la velocidad), velocímetro
grande, brillo y volumen verticales y la letra de la canción estilo karaoke debajo de la portada.

<table>
<tr>
<td width="50%"><img src="docs/screenshots/06-brillo-al-tocar.png" alt="Brillo encendido al tocarlo"><br>
<b>Barras traslúcidas:</b> brillo y volumen casi transparentes; al tocarlas se ven normales
(aquí el brillo) y al soltarlas vuelven a desvanecerse.</td>
<td width="25%"><img src="docs/screenshots/07-manejo-mitad.png" alt="Tablero a la mitad"><br>
<b>División a la mitad:</b> el diseño se reduce sin cortar nada.</td>
<td width="25%"><img src="docs/screenshots/08-manejo-angosto.png" alt="Tablero angosto"><br>
<b>División a ⅓:</b> lo esencial: saludo, iconos, velocímetro y controles.</td>
</tr>
</table>

### Botones ◁ ○ ▢ flotantes

![Botones flotantes apagados y encendidos](docs/screenshots/09-botones-flotantes.png)

En cualquier app (aquí Configuración): casi transparentes mientras no se usan (izquierda); el primer
toque los enciende (derecha) y se pueden arrastrar a donde no estorben.

## La tablet

| | |
|---|---|
| Modelo | Lenovo Tab 4 10 (**TB-X304F**, solo Wi-Fi) |
| Sistema | Android **8.1** (API 27) |
| Procesador | Qualcomm Snapdragon 425 (plataforma msm8937), 64 bits |
| Gráficos | Adreno 308, OpenGL ES 3.0 |
| Memoria | 2 GB RAM, 16 GB de almacenamiento |
| Pantalla | 10.1", 1280 × 800, 160 dpi |
| Conectividad | Wi-Fi (se usa el hotspot del celular), Bluetooth LE, GPS |
| Montaje | En el tablero del carro, siempre en horizontal, alimentada por el USB del carro |

Varias decisiones del proyecto salen de estas limitaciones: la GPU no soporta 3D en tiempo real
con motores modernos (Filament falla en el Adreno 308), así que el auto usa cuadros pre-renderizados;
y las animaciones están limitadas a ~30 cuadros por segundo para no calentar ni trabar la tablet.

## Qué hace

**Pantalla de inicio**
- Saludo según la hora ("Hola Alfre, buenos días / buenas tardes / buenas noches").
- Hyundai Accent rojo en 360°: se gira con el dedo, con inercia.
- Velocímetro con aguja, marcas y velocidad del GPS de la tablet.
- Clima propio de la tablet (Open-Meteo, sin cuenta): sol o luna según el día o la noche,
  descripción y sensación térmica en un numerito gris junto a la temperatura.
- Tarjeta de música (Spotify u otra app): portada, canción, barra para adelantar y controles.
- Brillo y volumen rápidos, batería, Bluetooth y Wi-Fi con intensidad real de la señal.
- Cajón de apps y menú de apagado (apagar pantalla, reiniciar, apagar).

**Modo manejo (pantalla dividida)**
- Al tocar **Mapa**, Waze (o Google Maps) se abre en ⅓ de la pantalla y el tablero en el resto.
- El auto gira para quedar de 3/4 trasero sobre una carretera en perspectiva, alineada con la
  dirección del auto; las líneas avanzan y las llantas giran según la velocidad del GPS.
- Velocímetro grande al centro; brillo y volumen verticales y traslúcidos (se encienden al tocarlos).
- Letra de la canción estilo karaoke debajo de la portada (LRCLIB), si la canción la tiene.
- El diseño se adapta al ancho de la división (⅓, ½ o ⅔) sin cortar nada.

**Botones ◁ ○ ▢ flotantes**
- Reemplazan la barra de Android (oculta para usar toda la pantalla); visibles en cualquier app,
  incluida la pantalla dividida, y ocultos en el inicio.
- Casi transparentes; el primer toque los enciende y se pueden arrastrar a cualquier lugar.
  Recuerdan su posición (una para pantalla completa y otra para la dividida).

**Integración con el carro**
- Al dar corriente el carro: se enciende la pantalla, abre el inicio, prende el Bluetooth y
  reanuda la música donde se quedó.
- Al apagarlo: pausa la música y deja que la pantalla se apague sola.
- Al encender o reiniciar la tablet: directo al launcher.
- El bloqueo de "deslizar" se quita solo; **un bloqueo con PIN, patrón o contraseña nunca se
  salta** (Android lo sigue pidiendo).

## Estructura

```
AutoSense/            App del launcher (paquete com.autosense.launcher, nombre visible "Hyundai")
  app/src/main/java/com/autosense/launcher/
    MainActivity.kt       Pantalla de inicio (HOME)
    DockActivity.kt       Tablero del modo manejo (pantalla dividida)
    Dashboard.kt          Lógica compartida: reloj, clima, música, GPS, sensores, pestañas
    SplitScreen.kt        Servicio de accesibilidad: pantalla dividida, botones flotantes
    NavPill.kt            Botones ◁ ○ ▢ flotantes y arrastrables
    CarPower.kt           Encendido/apagado con la corriente del carro, Bluetooth y música
    BootReceiver.kt       Al encender la tablet: pantalla encendida y directo al inicio
    CarTurntableView.kt   Auto 360° y llantas girando (cuadros en assets/)
    RoadView.kt           Carretera en perspectiva animada
    LyricsView.kt         Letra estilo karaoke
    SpeedGaugeView.kt, WifiSignalView.kt, BatteryView.kt, VerticalSeekBar.kt …
  app/src/main/assets/
    car360/               36 cuadros del auto (cada 10°)
    car_drive/            24 cuadros de las llantas girando (vista de manejo)
AutoSenseCompanion/   App opcional para el celular: envía el clima por Bluetooth (respaldo)
tools/render/         Página three.js que generó los cuadros del auto a partir del modelo 3D
```

## Cómo instalarla

1. Instala Android Studio y abre la carpeta `AutoSense` (Kotlin, minSdk 24, compileSdk 34).
2. En la tablet activa **Opciones de desarrollador → Depuración USB** y conéctala por USB.
3. Ejecuta la app (▶) o `./gradlew installDebug`.
4. Presiona Home y elige **Hyundai** como launcher predeterminado ("Siempre").
5. Permisos que la app pide o necesitan activarse a mano:
   - Ubicación (velocímetro y clima).
   - **Accesibilidad → Hyundai · Pantalla dividida** (pantalla dividida y botones flotantes).
   - Acceso a notificaciones (música y avisos de navegación).
   - Modificar ajustes del sistema (brillo y tiempo de pantalla).
   - Administrador del dispositivo (opción "Apagar pantalla").
6. Opcional: ocultar las barras de Android para usar toda la pantalla:
   `adb shell wm overscan 0,-24,0,-48` (para revertir: `adb shell wm overscan reset`).

Para probar el modo manejo sin manejar (solo en la versión de desarrollo):

```bash
adb shell am broadcast -a com.autosense.launcher.DEBUG_SPEED --ei kmh 80
```

## Limitaciones conocidas (Android 8.1)

- La pantalla dividida y los botones flotantes dependen del permiso de accesibilidad; si se fuerza
  el cierre de la app, Android lo desactiva y hay que volver a activarlo.
- Al arrastrar el divisor, Android 8.1 a veces se queda a medio cambiar de tamaño hasta otro toque.
- Spotify no comparte sus letras con otras apps; las letras vienen de LRCLIB. Si una canción solo
  tiene la letra sin tiempos, se reparte a lo largo de la canción (aproximada).
- Waze se usa en la versión 4.89, la última compatible y estable en esta tablet.

## Créditos

- Modelo 3D del auto: ["hyundai accent 2013"](https://sketchfab.com/I95XP) por **L95XP**,
  licencia [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Color modificado (rojo) y
  renderizado en cuadros.
- Clima: [Open-Meteo](https://open-meteo.com/) (CC BY 4.0).
- Letras sincronizadas: [LRCLIB](https://lrclib.net/).
- Render de los cuadros: [three.js](https://threejs.org/).
