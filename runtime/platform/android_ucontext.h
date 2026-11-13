// Android (bionic): no getcontext / makecontext / swapcontext. runtime/hle/fiber.c only needs a context switch
// between host stacks of its own (each fiber's first run calls a function that never returns), so this is that and
// nothing more, for AArch64: the callee-saved registers (x19-x28, fp, lr, sp, d8-d15) swapped by hand. Signal masks
// are not part of it (fiber.c never relies on them).
#pragma once
#include <stddef.h>
#include <stdint.h>
#include <string.h>

typedef struct {
    void *ss_sp;
    size_t ss_size;
} a_stack_t;

typedef struct a_ucontext {
    uint64_t r[13];  // x19..x28, x29 (fp), x30 (lr), sp      (offsets 0..96)
    uint64_t d[8];   // d8..d15                                (offset 104)
    a_stack_t uc_stack;
    struct a_ucontext *uc_link;
} a_ucontext_t;

#define ucontext_t a_ucontext_t
#define getcontext a_getcontext
#define makecontext a_makecontext
#define swapcontext a_swapcontext

static inline int a_getcontext(a_ucontext_t *uc) {
    memset(uc, 0, offsetof(a_ucontext_t, uc_stack));
    return 0;
}

// The new context starts in a_ctx_entry with the function in x19 (fiber.c's entry functions take no arguments and
// never return).
__attribute__((naked, used)) static void a_ctx_entry(void) {
    __asm__ volatile("blr x19\n"
                     "brk #0x1\n");
}

static inline void a_makecontext(a_ucontext_t *uc, void (*fn)(void), int argc) {
    (void)argc;
    uintptr_t top = ((uintptr_t)uc->uc_stack.ss_sp + uc->uc_stack.ss_size) & ~(uintptr_t)15;
    memset(uc->r, 0, sizeof uc->r);
    memset(uc->d, 0, sizeof uc->d);
    uc->r[0] = (uint64_t)(uintptr_t)fn;  // x19
    uc->r[11] = (uint64_t)(uintptr_t)a_ctx_entry;  // lr
    uc->r[12] = top;  // sp
}

// swapcontext(save, load): x0 = where to save this context, x1 = the one to resume.
__attribute__((naked, noinline, used)) static int a_swapcontext(a_ucontext_t *save, const a_ucontext_t *load) {
    __asm__ volatile(
        "stp x19, x20, [x0, #0]\n"
        "stp x21, x22, [x0, #16]\n"
        "stp x23, x24, [x0, #32]\n"
        "stp x25, x26, [x0, #48]\n"
        "stp x27, x28, [x0, #64]\n"
        "stp x29, x30, [x0, #80]\n"
        "mov x9, sp\n"
        "str x9, [x0, #96]\n"
        "stp d8, d9, [x0, #104]\n"
        "stp d10, d11, [x0, #120]\n"
        "stp d12, d13, [x0, #136]\n"
        "stp d14, d15, [x0, #152]\n"
        "ldp x19, x20, [x1, #0]\n"
        "ldp x21, x22, [x1, #16]\n"
        "ldp x23, x24, [x1, #32]\n"
        "ldp x25, x26, [x1, #48]\n"
        "ldp x27, x28, [x1, #64]\n"
        "ldp x29, x30, [x1, #80]\n"
        "ldr x9, [x1, #96]\n"
        "mov sp, x9\n"
        "ldp d8, d9, [x1, #104]\n"
        "ldp d10, d11, [x1, #120]\n"
        "ldp d12, d13, [x1, #136]\n"
        "ldp d14, d15, [x1, #152]\n"
        "mov w0, #0\n"
        "ret\n");
}
