#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>
#include <SDL3/SDL.h>
#include <SDL3/SDL_vulkan.h>
#include <adrenotools/driver.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdlib.h>

#include "rt.h"

static PFN_vkGetInstanceProcAddr instance_proc;
static PFN_vkGetDeviceProcAddr device_proc;
static bool custom_driver;

#define GLOBAL(ret, name, args, call) static PFN_##name driver_##name;
#define INSTANCE GLOBAL
#define DEVICE GLOBAL
#include "vulkan_commands.inc"
#undef GLOBAL
#undef INSTANCE
#undef DEVICE

static void load_global_commands(void) {
#define GLOBAL(ret, name, args, call) driver_##name = (PFN_##name)instance_proc(VK_NULL_HANDLE, #name);
#define INSTANCE(ret, name, args, call)
#define DEVICE INSTANCE
#include "vulkan_commands.inc"
#undef GLOBAL
#undef INSTANCE
#undef DEVICE
}

void pla_vulkan_select_driver(void) {
    const char *path = getenv("PLA_VULKAN_DRIVER");
    const char *driver_dir = getenv("PLA_DRIVER_DIR");
    const char *hook_dir = getenv("PLA_NATIVE_DIR");
    void *driver = path ? dlopen(path, RTLD_NOW | RTLD_LOCAL) : NULL;
    if (driver) {
        instance_proc = (PFN_vkGetInstanceProcAddr)dlsym(driver, "vk_icdGetInstanceProcAddr");
        if (instance_proc) {
            typedef VkResult (VKAPI_PTR *Negotiate)(uint32_t *);
            Negotiate negotiate = (Negotiate)dlsym(driver, "vk_icdNegotiateLoaderICDInterfaceVersion");
            uint32_t version = 5;
            if (negotiate && negotiate(&version) != VK_SUCCESS) instance_proc = NULL;
        } else {
            instance_proc = (PFN_vkGetInstanceProcAddr)dlsym(driver, "vkGetInstanceProcAddr");
        }
        if (instance_proc) {
            load_global_commands();
            custom_driver = driver_vkCreateInstance && driver_vkEnumerateInstanceExtensionProperties;
        }
        if (!custom_driver) {
            dlclose(driver);
            driver = NULL;
        }
    }
    if (path && !custom_driver && driver_dir && hook_dir) {
        driver = adrenotools_open_libvulkan(RTLD_NOW | RTLD_LOCAL, ADRENOTOOLS_DRIVER_CUSTOM,
                                            NULL, hook_dir, driver_dir, "libvulkan_freedreno.so", NULL, NULL);
        if (driver) {
            instance_proc = (PFN_vkGetInstanceProcAddr)dlsym(driver, "vkGetInstanceProcAddr");
            if (instance_proc) {
                load_global_commands();
                custom_driver = driver_vkCreateInstance && driver_vkEnumerateInstanceExtensionProperties;
            }
            if (!custom_driver) dlclose(driver);
        }
    }
    if (custom_driver) {
        LOG("[vk] Vulkan loader opened for imported driver %s\n", path);
        return;
    }
    if (path) LOG("[vk] Imported driver unavailable; using Android Vulkan\n");
    instance_proc = (PFN_vkGetInstanceProcAddr)SDL_Vulkan_GetVkGetInstanceProcAddr();
    if (!instance_proc) FATAL("SDL Vulkan loader unavailable: %s\n", SDL_GetError());
    load_global_commands();
}

void pla_vulkan_load_instance(VkInstance instance) {
    device_proc = (PFN_vkGetDeviceProcAddr)instance_proc(instance, "vkGetDeviceProcAddr");
    if (!device_proc) FATAL("Vulkan driver did not expose vkGetDeviceProcAddr\n");
#define GLOBAL(ret, name, args, call)
#define INSTANCE(ret, name, args, call) driver_##name = (PFN_##name)instance_proc(instance, #name);
#define DEVICE GLOBAL
#include "vulkan_commands.inc"
#undef GLOBAL
#undef INSTANCE
#undef DEVICE
}

void pla_vulkan_load_device(VkDevice device) {
#define GLOBAL(ret, name, args, call)
#define INSTANCE GLOBAL
#define DEVICE(ret, name, args, call) driver_##name = (PFN_##name)device_proc(device, #name);
#include "vulkan_commands.inc"
#undef GLOBAL
#undef INSTANCE
#undef DEVICE
}

// The activity's surface as it is now, with a reference of our own (NULL while the app is in the background). SDL's
// window property keeps the old pointer after Android destroys the surface (SDL_android.c only clears its own copy),
// so making a surface again from it after a trip to the background used freed memory (a crash in libutils).
static ANativeWindow *current_native_window(void) {
    JNIEnv *env = SDL_GetAndroidJNIEnv();
    jobject activity = env ? SDL_GetAndroidActivity() : NULL;
    if (!activity) return NULL;
    ANativeWindow *w = NULL;
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID get = (*env)->GetStaticMethodID(env, cls, "getNativeSurface", "()Landroid/view/Surface;");
    jobject s = get ? (*env)->CallStaticObjectMethod(env, cls, get) : NULL;
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env), s = NULL;
    if (s) {
        jclass sc = (*env)->GetObjectClass(env, s);
        jmethodID valid = (*env)->GetMethodID(env, sc, "isValid", "()Z");
        if (valid && (*env)->CallBooleanMethod(env, s, valid)) w = ANativeWindow_fromSurface(env, s);
        (*env)->DeleteLocalRef(env, sc);
        (*env)->DeleteLocalRef(env, s);
    }
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
    return w;
}
static ANativeWindow *surface_window;  // (held while the Vulkan surface made on it lives)

bool pla_vulkan_create_surface(SDL_Window *window, VkInstance instance, VkSurfaceKHR *surface) {
    if (!custom_driver) return SDL_Vulkan_CreateSurface(window, instance, NULL, surface);  // (SDL checks its window)
    PFN_vkCreateAndroidSurfaceKHR create =
        (PFN_vkCreateAndroidSurfaceKHR)instance_proc(instance, "vkCreateAndroidSurfaceKHR");
    if (!create) return SDL_SetError("Driver does not expose VK_KHR_android_surface");
    ANativeWindow *w = current_native_window();
    if (!w) return SDL_SetError("Android surface is unavailable");
    VkAndroidSurfaceCreateInfoKHR info = {.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR, .window = w};
    VkResult result = create(instance, &info, NULL, surface);
    if (result != VK_SUCCESS) { ANativeWindow_release(w); return SDL_SetError("vkCreateAndroidSurfaceKHR failed (%d)", result); }
    if (surface_window) ANativeWindow_release(surface_window);  // (the previous surface was destroyed before this one)
    surface_window = w;
    return true;
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance, const char *name) {
    return instance_proc(instance, name);
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice device, const char *name) {
    return device_proc(device, name);
}

#define GLOBAL(ret, name, args, call) \
    VKAPI_ATTR ret VKAPI_CALL name args { \
        if (!driver_##name) FATAL("Selected Vulkan driver did not expose %s\n", #name); \
        return driver_##name call; \
    }
#define INSTANCE GLOBAL
#define DEVICE GLOBAL
#include "vulkan_commands.inc"
