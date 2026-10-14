#include "VVerificationObserver.h"
#include "verilated.h"
#include "verilated_cov.h"
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>

static void require(bool condition, const std::string& message) {
    if (!condition) throw std::runtime_error(message);
}
int main(int argc, char** argv) {
    try {
        require(argc == 4, "usage: simulator seed limit output-directory");
        auto context = std::make_unique<VerilatedContext>();
        context->randReset(2); context->randSeed(std::stoul(argv[1]));
        auto dut = std::make_unique<VVerificationObserver>(context.get());
        const std::string output = argv[3];
        auto tick = [&]() {
            dut->clock=1; dut->eval(); context->timeInc(1);
            dut->clock=0; dut->eval(); context->timeInc(1);
        };
        dut->clock=0; dut->reset=1; dut->eval();
        for (unsigned epoch=0; epoch<2; ++epoch) {
            const auto prefix=output+"/reset-"+std::to_string(epoch);
            std::ofstream log(prefix+".jsonl"), events(prefix+".events.jsonl");
            require(bool(log)&&bool(events), "cannot open trace output");
            dut->reset=1; dut->eval(); tick(); tick();
            require(!dut->commitValid && !dut->io_MemWrite, "reset produced commit");
            dut->reset=0; dut->eval();
            uint64_t order=0, cycle=0, doneCycle=0; bool done=false;
            for (; cycle<std::stoull(argv[2]); ++cycle) {
                if(done) require(!dut->io_MemWrite, "late store after completion");
                events << "{\"cycle\":" << cycle << ",\"advance\":" << unsigned(dut->eventAdvance)
                  << ",\"instruction\":" << dut->eventInstr << ",\"forwardA\":" << unsigned(dut->forwardA)
                  << ",\"forwardB\":" << unsigned(dut->forwardB) << ",\"loadStall\":" << unsigned(dut->loadStall)
                  << ",\"storeStall\":" << unsigned(dut->storeStall) << ",\"redirect\":" << unsigned(dut->redirectE)
                  << ",\"rs1\":" << unsigned(dut->rs1E) << ",\"rs2\":" << unsigned(dut->rs2E)
                  << ",\"rdM\":" << unsigned(dut->rdM) << ",\"rdW\":" << unsigned(dut->rdW)
                  << ",\"writeM\":" << unsigned(dut->writeM) << ",\"writeW\":" << unsigned(dut->regWriteW)
                  << ",\"validM\":" << unsigned(dut->validM) << ",\"validW\":" << unsigned(dut->validW)
                  << ",\"lateM\":" << unsigned(dut->lateM) << "}\n";
                if(dut->commitValid && !done) {
                    const auto ins=dut->commitInstr;
                    const auto op=ins&127;
                    const bool load=op==3, store=op==35;
                    const unsigned width=1u<<((ins>>12)&3);
                    require(dut->commitRW == (store ? 1 : load ? 2 : 0), "decoded memory instruction disagrees with actual request");
                    if(store) require(dut->commitMask == (((1u << width)-1) << (dut->commitAddress&3)), "missing/wrong actual store byte mask");
                    log << "{\"epoch\":" << epoch << ",\"order\":" << order++ << ",\"cycle\":" << cycle
                      << ",\"pc\":" << dut->commitPC << ",\"instruction\":" << ins
                      << ",\"rd\":" << unsigned(dut->commitRd) << ",\"value\":" << dut->commitValue
                      << ",\"branchTaken\":" << unsigned(dut->commitBranchTaken) << ",\"x0\":" << dut->x0 << ",\"mem\":";
                    if(load||store) log << "{\"kind\":\"" << (load?"load":"store") << "\",\"address\":"
                      << dut->commitAddress << ",\"width\":" << width << ",\"data\":" << dut->commitData << "}";
                    else log << "null";
                    log << "}\n";
                    if(store && dut->commitAddress==0x8021ff00) {
                        require(width==4 && dut->commitData==1, "bad completion store");
                        done=true; doneCycle=cycle;
                        require(!dut->io_MemWrite, "late store after completion");
                    }
                }
                tick();
                if(done && cycle-doneCycle+1==8) break;
            }
            log.close(); events.close();
            VerilatedCov::write((prefix+".coverage.dat").c_str());
            VerilatedCov::zero();
            require(done && cycle-doneCycle+1==8, "timeout/missing completion observation");
            std::cout << "PASS epoch=" << epoch << " commits=" << order << " cycles=" << cycle << '\n';
        }
        dut->final();
    } catch(const std::exception& e) { std::cerr << "FAIL: " << e.what() << '\n'; return 1; }
}
