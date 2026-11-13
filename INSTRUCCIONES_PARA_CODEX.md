# PLA Recomp para Android: traspaso a Codex

Port de PokeL-ARecomp (recompilación estática de Pokémon Legends: Arceus 1.1.1) a Android, para el AYN Odin 3
(Snapdragon 8 Elite, Adreno 830, Android 15, kernel 6.6 con direcciones de 39 bits). Uso personal con el dump
propio del usuario. Fecha del traspaso: 13 de noviembre de 2026.

## Dónde está todo

- **Código (git):** `C:\Users\user\Documents\Codex\2026-11-12\pla`. El último commit es `7920e0a`. Lee
  `git log` primero: cada commit explica qué cambió y por qué.
- Fuente original sin tocar: `Desktop\PokeL-ARecomp-main` (ZIP de fabioschmidt01).
- Dump del juego: `Desktop\Pokemon legends arceus` (NSP base, actualización 1.1.1 y `keys\prod.keys` /
  `keys\title.keys`). Ya está extraído en `pla\extracted\` (`exefs/` + `romfs.bin` de 6,4 GB).
- APK más reciente: `Desktop\PLA Recomp Android\PLA-Recomp-wip.apk`. Capturas del usuario jugando:
  `Desktop\PLA Recomp Android\capturas\`.
- Odin por adb: serie **1234cf8d**. Siempre usa `-s 1234cf8d`, porque también hay un emulador conectado.
  Paquete: `com.jay.plarecomp`.
- En la Odin, los archivos del juego están en `/sdcard/Android/data/com.jay.plarecomp/files/extracted/`.
  El log del runtime es `/sdcard/Android/data/com.jay.plarecomp/files/lasrecomp.log` (léelo con
  `adb shell cat`).

## Cómo se compila

Hay tres piezas:

1. **Código del juego** (solo hace falta rehacerlo si cambian `tools/gen.py`, `runtime/cpu.h`, `simd*.h`
   o `gamelib.h`; tarda ~1 h en el i7-6700):
   - Generar: `tools/gen.py` con `--simd asm` en las bases desplazadas `0x1000000000` (main),
     `0x1008000000` (sdk, más `--hle ../runtime/hle/hle_symbols.txt`), `0x100a000000` (subsdk0) y
     `0x100b000000` (subsdk1). La salida va a `generated-android/<módulo>`. El venv está en
     `build/gen-venv` y necesita `PYTHONUTF8=1`.
   - Compilar: `build/gen-venv/Scripts/python.exe android/gamelib_ninja.py`, luego
     `ninja -C build/android-game -j7`. El ninja es el del SDK: `Android\Sdk\cmake\3.31.6\bin\ninja.exe`.
     El resultado es `build/android-game/libgame.so` (~270 MB).
2. **Runtime + APK:** `android\build-apk.ps1 -Install`. Compila `build/android-rt` con CMake/Ninja y el
   NDK 28.2, copia `libSDL3`, `libmain`, `libc++_shared` y `libgame` a `jniLibs`, ejecuta Gradle
   `assembleDebug`, instala y arranca la app. Para pasar variables de depuración al runtime:
   `adb shell am start -n com.jay.plarecomp/.PlaActivity --es env "LARECOMP_X=1;LARECOMP_Y=2"`.
3. **shaderc** viene de las fuentes del propio NDK, compilado con ndk-build en `build/shaderc/`. Es
   antiguo (ver pendientes).

Para las pruebas, mantén la Odin despierta con `adb shell svc power stayon true`. Si la pantalla se
bloquea, SDL se queda esperando la superficie y parece que la app se colgó.

## Lo que ya funciona (verificado en la Odin)

- **Espacio de direcciones:** el kernel es de 39 bits y en una app Android el rango 0x70–0x80 está
  ocupado. Todo el espacio del juego se desplazó −0x60_0000_0000 (a 0x10_0000_0000):
  - `GUEST_SHIFT` en `runtime/rt.h`, más `STACK_BASE` en `kernel.h`.
  - Copia desplazada de la tabla de direcciones de Zelda en `game_addresses.c`.
  - `gen.py` reubica los parches de `pla_patches.py`.
- **Runtime en bionic:**
  - Fibers con cambio de contexto propio en ARM64 (`runtime/platform/android_ucontext.h`).
  - `linux_compat.h`, búferes de `realpath` ampliados y sin relanzar el proceso.
  - `LARECOMP_RESOURCE_DIR` para las fuentes, que están en `files/res/` de la Odin.
- **Vulkan en la Adreno 830:**
  - Filtro de funciones no soportadas en `device.c` (le falta `uniformAndStorageBuffer8BitAccess`).
  - Swapchain sin rotación (`preTransform` identity), porque el panel es vertical; además se ignora
    `SUBOPTIMAL`.
- **Shaders:** `GL_EXT_texture_shadow_lod` pasa a `enable` en Android y `SHADER_TRANSLATOR_ID` se calcula
  en CMake. Si el id es fijo se reutiliza una caché vieja.
- El juego llega al menú de idioma, la selección de personaje y la playa de Obsidiana (el mundo 3D se ve) a
  **30 fps estables**.
- **Temperatura:** el bucle de eventos de SDL en Android nunca dormía y tenía un núcleo prime al 96 % y
  ~100 °C. Ahora sondea y duerme 4 ms (`ui_run` en `device.c`). Medido: 52–63 °C y ~31 % de CPU.

## A medias (hecho pero SIN verificar visualmente)

- **Memoria del juego para la GPU** (`backend_phys_pool` en `runtime/nvn/vulkan/exec.c`, llamado desde
  `MapPhysicalMemory` en `kernel.c`):
  - El problema: Adreno no tiene `VK_EXT_external_memory_host`, así que los shaders leían cero de la memoria
    global del juego. Por eso no se veían el texto, los nombres ni los personajes (el entrenador incluido).
  - La solución: el bloque de 3.205 MB que el juego mapea en una sola llamada (dirección `1b77b00000`) se
    reserva como memoria de Vulkan (tipo 6) y el mapeo del driver se mueve con `mremap` a esa dirección.
  - El log ya dice "guest heap imported for shader global memory: 1024 MB" y el juego corre.
  - **Falta comprobar en pantalla** que aparezcan el texto, los nombres y el entrenador. Si no aparecen,
    revisa la ventana `gmem_window_get` (el 1 GB superior) y si algo lee fuera de ella.
- **Pantalla completa:** está en `PlaActivity.java` (modo inmersivo y bajo el notch), sin comprobar.
- **Nombre del jugador:** en Android ya no se abre el teclado del sistema, que tapaba la pantalla; se usa
  el teclado en pantalla del runtime (`menu.mm`, `osk.mm`), que se maneja con el mando. Sin probar.

## Lo que falta (en este orden)

1. **Verificación en Odin:** el usuario confirmó que ya ve el texto y los modelos. Se comprobó en la playa
   que se dibujan la entrenadora, el profesor, los Pokémon y el HUD. El log registró el nombre «Jay».
2. **Parpadeo de texturas:** el usuario reportó parpadeo intermitente y nubes rojas y negras anómalas en la
   montaña del fondo. Aún falta capturar el defecto. El contador general `upload` mide staging de Vulkan,
   incluidas otras transferencias. Con `LARECOMP_VK_PROF=1`, `tex ... uploads` cuenta las subidas de texturas
   y sus bytes en formato guest. En la partida guardada en la playa, una vez cargada, se observaron
   30 fps, 111,5 MB de staging y cero subidas de texturas por intervalo de dos segundos. Forzar
   `LARECOMP_TEX_FULLHASH=1` en el mismo punto aumentó de 60 a 361 MB los datos hasheados por intervalo,
   con las mismas cero subidas. Queda pendiente la comparación visual mientras el defecto sea visible.
   En Android no hay seguimiento de escrituras por página: el kernel
   6.6 no tiene `UFFD WP_ASYNC` ni `PAGEMAP_SCAN`, así que se rehashea todo. Opciones:
   - un mecanismo propio de "dirty pages" (`mprotect` + `SIGSEGV`), o
   - comprobar si la pool de texturas cae dentro del bloque de memoria del driver y usar la GPU directamente.
   Esto también afecta a la temperatura y al rendimiento.
3. **Menú lanzador estilo Paper Mario** (`Documents\Codex\2026-11-02\pod\work\PaperBoat`, ver
   `android/app/src/main/java/dev/net64/paperboat/` y `WORK_STATUS.md`). El usuario pidió:
   - opciones de gráficos (resolución y similares);
   - **30 / 60 / 120 fps**. El runtime tiene un modo 60 fps (`rt_pla_is_60fps`, `fps60` en `larecomp.ini`)
     y "uncapped"; revisa `runtime/settings.c` y `runtime/hle/framepace.c`;
   - **drivers Turnip:** cargar `libvulkan_freedreno.so` desde `getFilesDir()` con
     `SDL_Vulkan_LoadLibrary` / `vk_icdGetInstanceProcAddr`, como en el port de Asura's Wrath
     (`C:\Ports\asura-fixes`);
   - controles y sistema;
   - **pantalla completa** (ya en Java, falta comprobarla).
   El arte debe ser original o aprobado por el usuario, y nunca se deben fingir opciones que no funcionen.
4. **Instalación de los archivos del juego dentro de la app**, como en Paper Mario: elegir el NSP base, la
   actualización 1.1.1 y `prod.keys` con SAF y extraerlos en el teléfono. En PC esto lo hace
   `tools/zaimport`, que es C# con LibHac, así que en Android hará falta un extractor nativo (hactool o
   similar). Ahora mismo se copian por adb.
5. **Controles táctiles** en pantalla, con un mando completo.
6. **Videos:** `runtime/hle/movie.c` está excluido porque falta FFmpeg para Android. subsdk0 es la librería
   de video del juego y ahora corre sin el HLE.
7. **shaderc/glslang moderno**, para que `GL_EXT_texture_shadow_lod` funcione y ningún shader falle.
8. **Temperatura en el mundo abierto:** un regulador térmico y ADPF, como en Asura y Blue Dragon. Revisar
   también los otros hilos.
9. Pasar las librerías prebuilt a `externalNativeBuild` de Gradle, como Paper Mario, y meter las fuentes en
   los assets en vez de copiarlas por adb.

## Reglas del usuario

- Nada se da por terminado sin probarlo en la Odin. Un build que compila no es una prueba.
- No se publican ni empaquetan archivos del juego para terceros: el APK lleva `libgame.so` generado del
  dump del usuario y es solo para su uso personal.
- Copia el APK final al escritorio (`Desktop\PLA Recomp Android`).
