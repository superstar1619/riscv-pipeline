#include "VRiscvPipeline.h"
#include "verilated.h"
#include <cstdint>
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
        constexpr uint32_t capacity = 128 * 1024;
        require(sigbase >= 0x80200000 &&
                uint64_t(sigbase) + expected.size() * 4 <= 0x80220000,
                "Signature outside DTIM");
        std::vector<uint8_t> ram(capacity), written(capacity);
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
        bool done = false;
        uint64_t cycles = 0, start = 0, elapsed = 0;
        unsigned markers = 0;
        for (; cycles < limit && !done; ++cycles) {
            require(bool(dut->io_MemWrite) == bool(dut->io_ByteMask),
                    "Write enable differs from actual byte mask");
            if (dut->io_MemWrite) {
                const uint32_t address = dut->io_IEUAdr;
                require(address >= 0x80200000 && address < 0x80220000,
                        "Store outside platform DTIM range");
                const uint32_t base = (address & (capacity - 1)) & ~3u;
                require(dut->io_Funct3 <= 2, "Unsupported actual store size");
                const unsigned size = 1u << dut->io_Funct3;
                for (unsigned lane = 0; lane < 4; ++lane) {
                    if ((dut->io_ByteMask >> lane) & 1) {
                        ram[base + lane] = dut->io_WriteData >> (8 * (lane % size));
                        written[base + lane] = 1;
                    }
                }
                if (address == 0x8021ff10 && dut->io_ByteMask == 15) {
                    if (dut->io_WriteData == 1 && markers == 0) { start = cycles; ++markers; }
                    else if (dut->io_WriteData == 2 && markers == 1) {
                        elapsed = cycles - start; ++markers;
                    } else throw std::runtime_error("Invalid benchmark marker sequence");
                }
                if (address == 0x8021ff00 && dut->io_ByteMask == 15) {
                    require(dut->io_WriteData == 1, "Software reported failure status");
                    done = true;
                }
            }
            tick();
        }
        require(done, "Cycle limit reached before completion");
        for (unsigned i = 0; i < expected.size(); ++i) {
            uint32_t actual = 0;
            for (unsigned lane = 0; lane < 4; ++lane) {
                const uint32_t addr = (sigbase & (capacity - 1)) + 4 * i + lane;
                require(written[addr], "Signature contains unwritten byte");
                actual |= uint32_t(ram[addr]) << (8 * lane);
            }
            require(actual == expected[i], "Signature word " + std::to_string(i) +
                    " differs: actual=" + std::to_string(actual) +
                    " expected=" + std::to_string(expected[i]));
        }
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
