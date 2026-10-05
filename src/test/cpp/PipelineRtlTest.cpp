#include "VRiscvPipeline.h"
#include "verilated.h"
#include <array>
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

int main(int argc, char** argv) {
    try {
        require(argc == 4, "Usage: pipeline-rtl events seed max-cycles");
        std::ifstream input(argv[1]);
        require(input.is_open(), "Cannot open expected events");
        std::vector<std::array<uint32_t, 5>> events;
        std::string line;
        while (std::getline(input, line)) {
            if (line.empty() || line[0] == '#') continue;
            std::istringstream fields(line);
            std::array<uint32_t, 5> event{};
            for (auto& field : event) require(bool(fields >> std::hex >> field), "Bad event");
            events.push_back(event);
        }
        require(!events.empty(), "Empty expected event sequence");
        const unsigned limit = std::stoul(argv[3]);
        require(limit > 0 && limit <= 1000000, "Invalid cycle bound");
        auto context = std::make_unique<VerilatedContext>();
        context->randSeed(std::stoi(argv[2]));
        context->randReset(2);
        auto dut = std::make_unique<VRiscvPipeline>(context.get());
        dut->clk = 0;
        dut->reset = 1;
        dut->eval();
        auto tick = [&]() {
            dut->clk = 1;
            dut->eval();
            context->timeInc(1);
            dut->clk = 0;
            dut->eval();
            context->timeInc(1);
        };
        unsigned total_events = 0;
        for (unsigned pass = 0; pass < 2; ++pass) {
            dut->reset = 1;
            dut->eval();
            for (unsigned i = 0; i < 2; ++i) {
                require(!dut->io_MemRW && !dut->io_MemWrite && !dut->io_ByteMask,
                        "Reset must suppress all memory requests");
                tick();
            }
            dut->reset = 0;
            dut->eval();
            unsigned index = 0;
            for (unsigned cycle = 0; cycle < limit; ++cycle) {
                require(bool(dut->io_MemWrite) == bool(dut->io_ByteMask),
                        "Write enable and actual byte mask differ");
                if (dut->io_MemRW) {
                    const auto where = std::string(argv[1]) + " pass=" +
                        std::to_string(pass) + " cycle=" + std::to_string(cycle) +
                        " event=" + std::to_string(index);
                    require(index < events.size(), where + ": extra memory event");
                    const auto& e = events[index++];
                    require(dut->io_IEUAdr == e[0], where + ": address mismatch");
                    require(dut->io_MemRW == e[1], where + ": request mismatch");
                    require(dut->io_Funct3 == e[2], where + ": function mismatch");
                    require(dut->io_ByteMask == e[3], where + ": byte mask mismatch");
                    if (e[3]) require(dut->io_WriteData == e[4], where + ": store data mismatch");
                }
                tick();
            }
            require(index == events.size(), "Cycle limit reached with missing events");
            total_events += index;
        }
        dut->final();
        std::cout << "PASS " << argv[1] << " seed=" << argv[2]
                  << " cycles=" << 2 * limit << " events=" << total_events
                  << " restart=PASS\n";
    } catch (const std::exception& e) {
        std::cerr << "FAIL: " << e.what() << '\n';
        return 1;
    }
}
