#include "VChapter9.h"
#include "verilated.h"
#if VM_COVERAGE
#include "verilated_cov.h"
#endif
#include <cstdlib>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>

struct CoverageSnapshot {
    VerilatedContext* context;
    ~CoverageSnapshot() {
#if VM_COVERAGE
        if (const char* path=std::getenv("COVERAGE_OUTPUT")) context->coveragep()->write(path);
#endif
    }
};

// This adapter records DUT evidence only. All expectations live outside RTL.
int main(int argc, char** argv) {
    try {
        if (argc != 5) throw std::runtime_error("usage: chapter9 seed cycle-limit trace.jsonl irq-enabled");
        const unsigned seed=std::stoul(argv[1]), limit=std::stoul(argv[2]);
        if (!limit || limit>1000000) throw std::runtime_error("invalid cycle limit");
        std::ofstream out(argv[3]);
        if (!out) throw std::runtime_error("cannot open trace");
        auto context=std::make_unique<VerilatedContext>();
        context->randSeed(seed); context->randReset(2);
        auto dut=std::make_unique<VChapter9>(context.get());
        CoverageSnapshot coverage{context.get()};
        bool irq=false;
        const bool irqEnabled=std::stoi(argv[4])!=0;
        auto pins=[&]() {
            dut->interrupts_MExtInt=0; dut->interrupts_SExtInt=0;
            dut->interrupts_MTimerInt=0; dut->interrupts_MSwInt=irq;
            dut->interrupts_TIME_REGW=0;
        };
        auto tick=[&]() {
            dut->clock=1; dut->eval(); context->timeInc(1);
            dut->clock=0; dut->eval(); context->timeInc(1);
        };
        bool allDone=true;
        for (unsigned repeat=0;repeat<2;++repeat) {
            irq=false; bool armed=false;
            dut->clock=0; dut->reset=1; pins(); dut->eval();
            for (unsigned n=0;n<3+seed%3;++n) tick();
            dut->reset=0; dut->eval();
            int done=-1;
            unsigned cycle=0;
            auto prefix=[&](const char* kind) {
                out << "{\"repeat\":" << repeat << ",\"cycle\":" << cycle << ",\"kind\":\"" << kind << "\"";
            };
            for (;cycle<limit;++cycle) {
                pins(); dut->eval();
                bool clearIrqAfterEdge=false;
                if (dut->commit_valid) {
                    prefix("commit");
                    out << ",\"pc\":" << uint32_t(dut->commit_pc32)
                        << ",\"instruction\":" << uint32_t(dut->commit_instruction32)
                        << ",\"rd\":" << unsigned(dut->commit_rd5)
                        << ",\"data\":" << uint32_t(dut->commit_wdata32) << "}\n";
                }
                if (dut->commit_trap) {
                    if (dut->trap_interrupt) clearIrqAfterEdge=true;
                    prefix("trap");
                    out << ",\"pc\":" << uint32_t(dut->trap_pc32)
                        << ",\"cause\":" << unsigned(dut->trap_cause6)
                        << ",\"interrupt\":" << (dut->trap_interrupt ? "true":"false")
                        << ",\"committed_m\":" << unsigned(dut->committed_m)
                        << ",\"committed_f\":" << unsigned(dut->committed_f)
                        << ",\"mswint\":" << (dut->interrupts_MSwInt ? "true":"false") << "}\n";
                }
                if (dut->event_valid) {
                    prefix("bus");
                    out << ",\"event\":" << unsigned(dut->event_kind4)
                        << ",\"address\":" << uint32_t(dut->event_addr32)
                        << ",\"data\":" << uint32_t(dut->event_wdata32)
                        << ",\"readData\":" << uint32_t(dut->event_rdata32)
                        << ",\"mask\":" << unsigned(dut->event_mask4) << "}\n";
                }
                if (dut->store_valid) {
                    prefix("store");
                    out << ",\"address\":" << uint32_t(dut->store_addr32)
                        << ",\"data\":" << uint32_t(dut->store_data32)
                        << ",\"mask\":" << unsigned(dut->store_mask4) << "}\n";
                    if (irqEnabled && dut->store_addr32==0x8000fe00u && dut->store_data32==1) armed=true;
                    if (dut->store_addr32==0x8000ff00u && dut->store_data32==0x5au && dut->store_mask4==15)
                        done=int(cycle);
                }
                if (dut->bus_wait || dut->ifu_wait || dut->lsu_wait) {
                    prefix("sample");
                    out << ",\"bus_wait\":" << unsigned(dut->bus_wait)
                        << ",\"ifu_wait\":" << unsigned(dut->ifu_wait)
                        << ",\"lsu_wait\":" << unsigned(dut->lsu_wait)
                        << ",\"committed_f\":" << unsigned(dut->committed_f)
                        << ",\"committed_m\":" << unsigned(dut->committed_m) << "}\n";
                }
                if (armed && !irq && (dut->committed_m || dut->committed_f)) {
                    irq=true; armed=false;
                    pins(); dut->eval(); // pin and committed evidence share this sampling boundary
                    prefix("irq");
                    out << ",\"asserted\":true,\"committed_m\":" << unsigned(dut->committed_m)
                        << ",\"committed_f\":" << unsigned(dut->committed_f) << "}\n";
                }
                tick();
                if (clearIrqAfterEdge) {
                    irq=false; pins(); dut->eval(); // accepted trap edge has completed
                    prefix("irq");
                    out << ",\"asserted\":false,\"afterAcceptedTrapEdge\":true,\"committed_m\":"
                        << unsigned(dut->committed_m) << ",\"committed_f\":" << unsigned(dut->committed_f) << "}\n";
                }
                if (done>=0 && int(cycle)>=done+8) break;
            }
            prefix("end"); out << "}\n";
            allDone &= done>=0 && int(cycle)>=done+8;
        }
        dut->final();
        if (!allDone) { std::cerr << "timeout or incomplete observation window\n"; return 1; }
        return 0;
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
