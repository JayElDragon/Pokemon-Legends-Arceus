# Arceus Recomp para Android

Port para Android (probado en la Odin 3) de PokeL-ARecomp, la recompilación estática de Pokémon Legends: Arceus.

- Este repositorio tiene el código completo del port, **sin** nada del juego: ni el juego, ni las keys, ni el código generado, ni `libgame.so`, ni archivos derivados del juego.
- El APK no trae el juego. Cada jugador usa su propia copia, y el código del juego (`libgame.so`) **se genera en el celular** a partir de ella.

El ícono usa el logo de Pokémon, que es marca registrada. Para una versión pública conviene un ícono propio.

## Cómo se juega

1. Instalar el APK.
2. En **Archivos del juego**, elegir tu `prod.keys`, tu juego (`.nsp` o `.xci`) y tu update (`.nsp`).
3. El port los lee donde están y los descifra con hactool, que va integrado. Aplica el update y guarda `exefs/` y `romfs.bin` dentro de la app.
4. Después traduce el código del juego a C y lo compila en el propio celular con Python, el traductor, clang y lld, que van en el APK. Al final enlaza `libgame.so`.
   - En la Odin 3 tarda unos 10 minutos.
   - Si se interrumpe, retoma donde quedó.
5. Aparece **Jugar**. Ya se pueden borrar del celular los archivos del juego.

## Qué funciona

- **Rendimiento en la Odin:** 60 fps a 60 Hz con resolución al 100 %, a unos 52 °C, con Turnip y ADPF.
- **Perfiles de rendimiento automáticos** (Video > Android > Rendimiento). Se eligen según la GPU (Adreno, Mali, Xclipse, PowerVR) y la RAM:

  | Perfil | Ajustes |
  |---|---|
  | Alto | 100 %, 60 fps |
  | Medio | Resolución dinámica (mínimo 60 %), 60 fps |
  | Ligero | 75 %, 30 fps, sombras a la mitad |

- **Bajar a 30 fps cuando haga falta.** El juego cuenta frames, así que por debajo de 60 iría en cámara lenta. Con esta opción pasa a 30 estables.
- **Mali y PowerVR:**
  - las texturas BC se decodifican en el CPU;
  - BC4 y BC5 usan formatos de 1 y 2 canales;
  - con 8 GB de RAM o menos, las texturas grandes se cargan sin su nivel más grande.
- **Adreno 6xx con el driver de Qualcomm:** extended dynamic state y dynamic vertex input se apagan; esos estados van en los pipelines.
- **Drivers:** acepta cualquier paquete de AdrenoTools. En GPUs que no son Adreno usa el driver del sistema.
- **Música y voces:** el servicio hwopus está hecho con libopus 1.5.2 (BSD).
- **Partidas:** se pueden exportar e importar en `.zip`, o la `main` suelta de JKSV.
- **Registros:** Android > Soporte > Exportar registros guarda un `.zip` con los logs y los ajustes, para reportes.
- **Controles:** controles táctiles. Al tocar la pantalla ya no se lanzan Pokébolas.
- **Si el juego se cierra solo,** al volver a abrirlo ofrece opciones seguras.

## Pendiente

- El cartel blanco del nombre del lugar al cargar la partida.
- Probar los perfiles en celulares reales con Mali, PowerVR y Adreno 6xx. En la Odin solo se simularon.

## Compilar

- `android/build-apk.ps1` compila el runtime con ninja (NDK 28.2) y arma el APK con Gradle.
  - `-WithoutGame`: el APK sin `libgame.so`.
  - `-ToolchainDir <dir>`: le mete el compilador del celular.
- `android/gen/stage-toolchain.py` arma esa carpeta. Necesita Python 3.14 para Android, clang y lld 19 compilados para Android, y el NDK (ver `android/gen/README.md`).
- `android/gamelib_ninja.py` compila `libgame.so` en PC a partir de tus propios archivos.
