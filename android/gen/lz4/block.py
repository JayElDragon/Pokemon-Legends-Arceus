"""lz4.block.decompress for the game code generator on Android (tools/nso.py), through liblz4.so with ctypes
(python-lz4 is a C extension with no Android build). PLA_LZ4_LIB: the library's path (the app's native libraries)."""
import ctypes
import os

_lib = ctypes.CDLL(os.environ.get('PLA_LZ4_LIB', 'liblz4.so'))
_lib.LZ4_decompress_safe.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_int, ctypes.c_int]
_lib.LZ4_decompress_safe.restype = ctypes.c_int


def decompress(source, uncompressed_size=-1, return_bytearray=False, dict=None):
    if uncompressed_size is None or uncompressed_size < 0:
        raise ValueError('uncompressed_size is required on Android')
    out = ctypes.create_string_buffer(uncompressed_size)
    n = _lib.LZ4_decompress_safe(bytes(source), out, len(source), uncompressed_size)
    if n < 0:
        raise ValueError('LZ4 data is corrupt (%d)' % n)
    data = out.raw[:n]
    return bytearray(data) if return_bytearray else data
