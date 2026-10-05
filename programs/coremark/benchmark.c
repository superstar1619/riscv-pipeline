/* Fixed-iteration functional/simulation driver. CoreMark algorithm files remain
 * unmodified; initialization follows upstream core_main, with host timing markers.
 */
#include "coremark.h"
extern ee_s32 get_seed_32(int i);
static ee_u8 data[TOTAL_DATA_SIZE];
static core_results result;
volatile ee_u32 benchmark_signature[7];

int benchmark_main(void) {
  result.seed1 = (ee_s16)get_seed_32(1);
  result.seed2 = (ee_s16)get_seed_32(2);
  result.seed3 = (ee_s16)get_seed_32(3);
  result.iterations = (ee_u32)get_seed_32(4);
  result.execs = (ee_u32)get_seed_32(5);
  result.size = TOTAL_DATA_SIZE / NUM_ALGORITHMS;
  result.memblock[0] = data;
  for (unsigned i = 0; i < NUM_ALGORITHMS; ++i)
    result.memblock[i + 1] = data + result.size * i;
  result.list = core_list_init(result.size, result.memblock[1], result.seed1);
  core_init_matrix(result.size, result.memblock[2],
    (ee_s32)result.seed1 | ((ee_s32)result.seed2 << 16), &result.mat);
  core_init_state(result.size, result.seed1, result.memblock[3]);
  start_time();
  iterate(&result);
  stop_time();
  ee_u16 seedcrc = crc16(result.seed1, 0);
  seedcrc = crc16(result.seed2, seedcrc);
  seedcrc = crc16(result.seed3, seedcrc);
  seedcrc = crc16((ee_s16)result.size, seedcrc);
  /* Fixed official CRCs for 2K performance/validation, core_main known_id 3/4. */
  unsigned errors = (result.crclist != (VALIDATION_RUN ? 0xe3c1 : 0xe714))
    + (result.crcmatrix != (VALIDATION_RUN ? 0x0747 : 0x1fd7))
    + (result.crcstate != (VALIDATION_RUN ? 0x8d84 : 0x8e3a))
    + (seedcrc != (VALIDATION_RUN ? 0x18f2 : 0xe9f5));
  benchmark_signature[0] = seedcrc;
  benchmark_signature[1] = result.crclist;
  benchmark_signature[2] = result.crcmatrix;
  benchmark_signature[3] = result.crcstate;
  benchmark_signature[4] = result.crc;
  benchmark_signature[5] = errors;
  benchmark_signature[6] = result.iterations;
  *(volatile ee_u32 *)0x8021ff00 = errors ? 3 : 1;
  for (;;) __asm__ volatile ("nop");
}
