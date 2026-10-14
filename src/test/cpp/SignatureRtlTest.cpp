#include "VRiscvPipeline.h"
#include "verilated.h"
#include "SignatureProtocol.h"
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

static void require(bool ok, const std::string& message) {
    if (!ok) throw std::runtime_error(message);
}
int main(int argc, char** argv) {
    try {
        require(argc == 6, "Usage: signature-rtl reference sig-base seed max-cycles label");
        std::ifstream input(argv[1]);
        require(input.is_open(), "Cannot read reference signature");
        std::vector<uint32_t> expected;
        uint32_t value;
        while (input >> std::hex >> value) expected.push_back(value);
        require(input.eof() && !expected.empty(), "Invalid reference signature");
        const uint32_t sigbase = std::stoul(argv[2], nullptr, 16);
        const uint64_t limit = std::stoull(argv[4]);
        require(limit && limit <= 100000000, "Invalid cycle limit");
        SignatureProtocol protocol(sigbase, expected);
        auto context = std::make_unique<VerilatedContext>();
        context->randSeed(std::stoi(argv[3]));
        context->randReset(2);
        auto dut = std::make_unique<VRiscvPipeline>(context.get());
        dut->clk = 0;
        dut->reset = 1;
        dut->eval();
        auto tick = [&]() {
            dut->clk = 1; dut->eval(); context->timeInc(1);
            dut->clk = 0; dut->eval(); context->timeInc(1);
        };
        tick(); tick();
        dut->reset = 0; dut->eval();
        auto saveSignatures = [&]() {
            if (const char* path = std::getenv("SIGNATURE_OUTPUT")) {
                std::ofstream signature(path);
                require(signature.is_open(), "Cannot write actual signature");
                for (uint32_t word : protocol.reconstructed()) signature << std::hex << word << '\n';
            }
            // Preserve the existing store-reconstructed output and save the independent
            // CPU stream alongside it for replay and three-way comparison.
            if (const char* path = std::getenv("SIGNATURE_OUTPUT")) {
                std::ofstream readback(std::string(path) + ".cpu");
                require(readback.is_open(), "Cannot write CPU readback signature");
                for (uint32_t word : protocol.readback()) readback << std::hex << word << '\n';
            }
        };
        unsigned postDoneCycles = 0;
        uint64_t cycles = 0, start = 0, elapsed = 0;
        unsigned markers = 0;
        try {
            for (; cycles < limit && postDoneCycles < 8; ++cycles) {
                const bool observingAfterDone = protocol.done();
                require(bool(dut->io_MemWrite) == bool(dut->io_ByteMask),
                        "Write enable differs from actual byte mask");
                if (dut->io_MemWrite) {
                    const uint32_t address = dut->io_IEUAdr;
                    protocol.observe(address, dut->io_ByteMask, dut->io_Funct3, dut->io_WriteData);
                    if (address == 0x8021ff10 && dut->io_ByteMask == 15) {
                        if (dut->io_WriteData == 1 && markers == 0) { start = cycles; ++markers; }
                        else if (dut->io_WriteData == 2 && markers == 1) {
                            elapsed = cycles - start; ++markers;
                        } else throw std::runtime_error("Invalid benchmark marker sequence");
                    }
                }
                // The completion edge is followed by eight complete observation cycles.
                tick();
                if (observingAfterDone) ++postDoneCycles;
            }
        } catch (...) {
            saveSignatures();
            throw;
        }
        saveSignatures();
        protocol.finish();
        require(postDoneCycles == 8, "Cycle limit reached before post-completion observation");
        if (std::string(argv[5]).find("coremark") == 0)
            require(markers == 2 && elapsed > 0, "Missing CoreMark timing markers");
        dut->final();
        std::cout << "PASS " << argv[5] << " seed=" << argv[3] << " cycles=" << cycles
                  << " signature_words=" << expected.size();
        if (elapsed) std::cout << " benchmark_cycles=" << elapsed;
        std::cout << '\n';
    } catch (const std::exception& e) {
        std::cerr << "FAIL: " << e.what() << '\n';
        return 1;
    }
}
