# dumbOS — previa interactiva

Previa jugable del ecosistema dumbOS. No es una maqueta: es el **mismo motor** que usa la APK
(mapa de bits 5×7, iconos 16×16, listas con imán a fila, cromo de teclas blandas) reimplementado
en JavaScript sobre `<canvas>`.

```bash
node web/serve.js 8080     # http://localhost:8080
```

| Fichero | Contenido |
|---|---|
| `pixel-widgets.js` | **Generado** por `tools/gen.py`: glifos e iconos, idénticos a `PixelWidgets.kt`. |
| `pixel-ui.js` | Tipografía, scroll, paletas y datos de ejemplo del "sistema". |
| `apps.js` | Las nueve pantallas, con las mismas medidas que las activities de Kotlin. |
| `preview.js` | Cuerpo del 3310, cruceta, teclas y enrutado de eventos. |
| `audio.js` | Radio sintetizada (portadora + estática) con WebAudio. |
| `index.html` | Carcasa del dispositivo y estilos. |

## Controles

| Tecla | Acción |
|---|---|
| `↑` `↓` | Mover la selección |
| `←` `→` | Mover / cambiar dial |
| `Enter` | OK / abrir |
| `Retroceso` `Esc` | Atrás |
| `M` | Menú (tecla blanda derecha) |
| `0-9` `*` `#` | Teclado numérico |

También se puede usar el ratón o el dedo sobre la carcasa del teléfono.

## Diferencias con la app

- No hay SMS/contactos reales: la previa usa conversaciones de ejemplo.
- El navegador no descarga red: sirve páginas de demostración con el mismo formato de texto.
- WhatsApp muestra tráfico puenteado de ejemplo (en la APK sale del plugin de notificaciones).
- La radio suena, pero toca una portadora, no emisoras de verdad (en Android tampoco: no hay API pública de FM).

Todo lo demás —el dibujo, la tipografía, los diálogos, los menús, el scroll— es el mismo código
traducido: sirve para validar la estética y los flujos antes de instalar la APK.
