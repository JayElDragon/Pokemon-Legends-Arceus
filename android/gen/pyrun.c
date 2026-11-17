// The game code generator's Python on Android (android/gen/README): libpython3.14.so from python.org's Android
// package, embedded - Android has no python executable. As a program (adb tests): pyrun <script> [args...].
// In the app, android/gamecode.c calls Py_BytesMain the same way in a forked worker.
#include <Python.h>

int main(int argc, char **argv) { return Py_BytesMain(argc, argv); }
