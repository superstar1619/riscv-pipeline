#include "coremark.h"

volatile ee_s32 seed1_volatile = VALIDATION_RUN ? 0x3415 : 0;
volatile ee_s32 seed2_volatile = VALIDATION_RUN ? 0x3415 : 0;
volatile ee_s32 seed3_volatile = 0x66;
volatile ee_s32 seed4_volatile = ITERATIONS;
volatile ee_s32 seed5_volatile = ALL_ALGORITHMS_MASK;
ee_u32 default_num_contexts = 1;

/* Execution timing is measured by the Verilator host between these store markers.
 * No fabricated CSR clock or wall-time score is returned to upstream core_main.
 * The functional harness uses upstream iterate rather than upstream timed main.
 */
void start_time(void) { *(volatile ee_u32 *)0x8021ff10 = 1; }
void stop_time(void) { *(volatile ee_u32 *)0x8021ff10 = 2; }
CORE_TICKS get_time(void) { return 0; }
secs_ret time_in_secs(CORE_TICKS ticks) { (void)ticks; return 0; }
void portable_init(core_portable *p, int *argc, char *argv[]) {
  (void)argc; (void)argv; p->portable_id = 1;
}
void portable_fini(core_portable *p) { p->portable_id = 0; }
int ee_printf(const char *format, ...) { (void)format; return 0; }
