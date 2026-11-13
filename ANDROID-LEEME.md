# Arceus Recomp para Android

Port para Android (probado en la Odin 3) de PokeL-ARecomp, la recompilación estática de Pokémon Legends: Arceus.

- Este repositorio: el código completo del port (sin juego, keys, código generado ni archivos derivados del juego).
- El APK no se publica aquí: el juego lo pone cada jugador con su propia copia.

## IMPORTANTE antes de publicar

El APK de ahora es para **uso personal**. Lleva dentro `libgame.so`, que es el código del juego de Switch traducido a Android, así que no se puede publicar.

Este repositorio **no** lleva nada del juego: ni el juego, ni las keys, ni el código generado, ni `libgame.so`.

El ícono usa el logo de Pokémon, que es marca registrada. Para una versión pública conviene un ícono propio.

## Qué funciona

- **Rendimiento:** 60 fps a 60 Hz con resolución 100 %, a unos 52 °C de CPU en la Odin. Usa Turnip, 4 núcleos y el aviso de rendimiento ADPF con tiempo de CPU y de GPU.
- **Menú del port (Video > Android):**
  - controles táctiles;
  - núcleos de CPU (Todos / 4 / 2);
  - suavizado (Apagado / SMAA / Suave, que es el predeterminado);
  - distancia de dibujo;
  - driver Vulkan.
- **Controles:** al tocar la pantalla ya no se lanzan Pokébolas. Los controles táctiles se esconden al usar el mando.
- **Nombre e ícono:** "Arceus Recomp", con ícono adaptativo que llena la forma del launcher.
- **Game Files, nuevo:**
  - El jugador elige su `prod.keys`, su juego (`.nsp` o `.xci`) y su update (`.nsp`).
  - El port los lee donde están, sin copiarlos antes. Los descifra con hactool, que va integrado en `third_party/hactool`. Aplica el update y guarda dentro de la app `extracted/exefs/` y `extracted/romfs.bin`.
  - Mientras tanto se ve una pantalla de carga con el porcentaje y los pasos.
  - Al terminar avisa que ya se pueden borrar esos archivos del celular.
  - Se probó en PC (WSL) con tu juego, tu update 1.1.1 y tus keys: el resultado es **idéntico byte a byte** al de la herramienta de PC.

## Pendiente

1. **Probar Game Files en la Odin.** La Odin estaba desconectada y no se pudo hacer. Pasos: Ajustes > Archivos del juego > Borrar, y luego elegir los 3 archivos y pulsar "Preparar juego".
2. **Compilar el código del juego en el celular.** Hace falta para publicar sin `libgame.so`. En PC lo hacen un traductor en Python (`tools/gen.py`) y clang. En Android habría que meter Python y un compilador en el APK; es lo más grande que falta.
3. **El cartel blanco del nombre del lugar al iniciar la partida.** El diagnóstico está listo: arrancar con `--es env LARECOMP_TEX_DUMP_ONLY=550x70,1200x256,694x496,200x40` guarda las texturas sospechosas en `textures/dump`.

## Compilar

- `android/build-apk.ps1` compila el runtime con ninja (NDK 28.2) y arma el APK con Gradle.
- `android/gamelib_ninja.py` compila el código del juego, que sale de `tools/gen.py`, a partir de tus propios archivos.
