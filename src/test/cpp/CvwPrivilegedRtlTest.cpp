#include "VCvw.h"
#include "verilated.h"
#include <cstdint>
#include <fstream>
#include <iostream>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

static void require(bool ok, const std::string& message) {
    if (!ok) throw std::runtime_error(message);
}
// Public M-stage ports only. Signatures are read by real CPU LW and streamed by SW.
int main(int argc, char** argv) {
    try {
        require(argc == 6, "usage: cvw-rtl reference usedBytes seed limit result.json");
        std::ifstream input(argv[1]);
        std::vector<uint32_t> expected;
        uint32_t value;
        while (input >> std::hex >> value) expected.push_back(value);
        require(input.eof() && expected.size() == 1024, "invalid reference length");
        const unsigned used = std::stoul(argv[2]), seed = std::stoul(argv[3]);
        const unsigned limit = std::stoul(argv[4]);
        require(limit > 0 && limit <= 1000000, "invalid cycle limit");
        std::ofstream output(argv[5]);
        require(output.is_open(), "cannot open result");
        auto context = std::make_unique<VerilatedContext>();
        context->randSeed(seed); context->randReset(2);
        auto dut = std::make_unique<VCvw>(context.get());
        unsigned pins = 0; uint64_t time = 0;
        auto drive = [&]() {
            dut->interrupts_MSwInt = pins == 3;
            dut->interrupts_MTimerInt = pins == 7;
            dut->interrupts_SExtInt = pins == 9;
            dut->interrupts_MExtInt = pins == 11;
            dut->interrupts_TIME_REGW = time;
        };
        auto tick = [&]() {
            dut->clk = 1; dut->eval(); context->timeInc(1);
            dut->clk = 0; dut->eval(); context->timeInc(1);
        };
        bool passed = true;
        output << "{\"seed\":" << seed << ",\"runs\":[";
        for (unsigned repeat = 0; repeat < 2; ++repeat) {
            pins = 0; time = 0;
            dut->clk = 0; dut->reset = 1; drive(); dut->eval();
            for (unsigned n = 0; n < 3 + seed % 3; ++n) tick();
            dut->reset = 0; dut->eval();
            int scheduled = -1; unsigned requested = 0;
            int doneAt = -1;
            unsigned cycle = 0, length = ~0u, guard = 0, canary = ~0u;
            unsigned postStores = 0;
            bool done = false;
            std::vector<uint32_t> actual;
            std::vector<std::string> events;
            std::string failure;
            for (; cycle < limit && (doneAt < 0 || int(cycle) < doneAt + 9); ++cycle) {
                if (int(cycle) == scheduled) {
                    pins = requested & 0xff;
                    if (pins == 5) { time = 2; pins = 0; }
                }
                drive(); dut->eval();
                if (bool(dut->io_MemWrite) != bool(dut->io_ByteMask)) {
                    failure = "write/mask inconsistency"; break;
                }
                if (dut->io_MemWrite) {
                    unsigned address = dut->io_IEUAdr, data = dut->io_WriteData;
                    std::ostringstream event;
                    event << "{\"cycle\":" << cycle << ",\"address\":" << address
                          << ",\"data\":" << data << ",\"mask\":" << unsigned(dut->io_ByteMask) << "}";
                    // Keep protocol and test writes, not the repetitive startup initializer.
                    if (address >= 0xff00 || cycle > 15000) events.push_back(event.str());
                    if (done) ++postStores;
                    if (address >= 0xff00 && dut->io_ByteMask != 15) {
                        failure = "nonword protocol store"; break;
                    }
                    if (address == 0xff20) {
                        if (data == 0) { pins = 0; time = 0; scheduled = -1; }
                        else {
                            requested = data;
                            scheduled = cycle + ((data & 0x100) ? 96 : 1);
                        }
                    } else if (address == 0xff00) actual.push_back(data);
                    else if (address == 0xff04) length = data;
                    else if (address == 0xff08) guard = data;
                    else if (address == 0xff0c) canary = data;
                    else if (address == 0xff10) {
                        if (data != 0x5a) failure = "invalid completion marker";
                        done = data == 0x5a; doneAt = cycle;
                    }
                }
                tick();
            }
            const unsigned postCycles = doneAt >= 0 ? cycle - unsigned(doneAt) - 1 : 0;
            if (failure.empty() && !done) failure = "timeout: missing completion marker";
            if (failure.empty() && postCycles != 8) failure = "incomplete observation window";
            if (failure.empty() && postStores) failure = "side effect after completion";
            if (failure.empty() && length != used) failure = "wrong signature length";
            if (failure.empty() && (guard != 0xcafef00d || canary != 0)) failure = "corrupted sentinel";
            if (failure.empty() && actual.size() != expected.size()) failure = "missing/extra signature words";
            if (failure.empty()) for (unsigned i = 0; i < actual.size(); ++i) {
                if (actual[i] != expected[i]) {
                    failure = "signature mismatch word " + std::to_string(i) +
                              " actual=" + std::to_string(actual[i]) + " expected=" + std::to_string(expected[i]);
                    break;
                }
            }
            passed = passed && failure.empty();
            if (repeat) output << ',';
            output << "{\"repeat\":" << repeat << ",\"cycles\":" << cycle
                   << ",\"pass\":" << (failure.empty() ? "true" : "false")
                   << ",\"failure\":\"" << failure << "\",\"length\":" << length
                   << ",\"guard\":" << guard << ",\"canary\":" << canary
                   << ",\"done\":" << (done ? "true" : "false") << ",\"postCycles\":" << postCycles
                   << ",\"postStores\":" << postStores << ",\"words\":[";
            for (unsigned i = 0; i < actual.size(); ++i) { if (i) output << ','; output << actual[i]; }
            output << "],\"events\":[";
            for (unsigned i = 0; i < events.size(); ++i) { if (i) output << ','; output << events[i]; }
            output << "]}";
            std::cout << "repeat=" << repeat << " cycles=" << cycle << ' '
                      << (failure.empty() ? "PASS" : failure) << '\n';
        }
        dut->final();
        output << "],\"pass\":" << (passed ? "true" : "false") << "}\n";
        return passed ? 0 : 1;
    } catch (const std::exception& exc) {
        std::cerr << exc.what() << '\n'; return 2;
    }
}
