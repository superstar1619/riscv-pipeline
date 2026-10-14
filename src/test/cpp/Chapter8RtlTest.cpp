#include "VChapter8.h"
#include "verilated.h"
#if VM_COVERAGE
#include "verilated_cov.h"
#endif
#include <cstdlib>
#include <array>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

// Keep failed-run coverage too; adapters merge only fully passing runs.
struct CoverageSnapshot {
    VerilatedContext* context;
    ~CoverageSnapshot() {
#if VM_COVERAGE
        if (const char* output = std::getenv("COVERAGE_OUTPUT"))
            context->coveragep()->write(output);
#endif
    }
};

// Only production top-level ports are used. Observe stores before their commit edge.
using Event = std::array<uint32_t, 3>;
static void require(bool ok, const std::string& message) {
    if (!ok) throw std::runtime_error(message);
}
static void writeEvent(std::ostream& out, const Event& e, unsigned cycle) {
    out << "{\"cycle\":" << cycle << ",\"address\":\"" << e[0]
        << "\",\"data\":\"" << e[1] << "\",\"mask\":\"" << e[2] << "\"}";
}
int main(int argc, char** argv) {
    try {
        require(argc == 10, "usage: checker events seed limit pins timeInitial timeRaised delayed result.json");
        std::ifstream input(argv[1]);
        require(input.is_open(), "cannot open expected events");
        std::vector<Event> expected;
        std::string line;
        while (std::getline(input, line)) {
            if (line.empty()) continue;
            std::istringstream fields(line);
            Event e{};
            for (auto& n : e) require(bool(fields >> std::hex >> n), "malformed event");
            std::string extra;
            require(!(fields >> extra), "extra event field");
            expected.push_back(e);
        }
        require(!expected.empty(), "empty expectations");
        const unsigned seed = std::stoul(argv[2]);
        const unsigned limit = std::stoul(argv[3]);
        require(limit > 0 && limit <= 10000, "invalid cycle bound");
        const unsigned requestedPins = std::stoul(argv[4]);
        const uint64_t initialTime = std::stoull(argv[5]);
        const uint64_t raisedTime = std::stoull(argv[6]);
        const bool delayed = std::stoi(argv[7]) != 0;
        // argv[8] is reserved for future input schedules; must be zero.
        require(std::string(argv[8]) == "0", "unsupported schedule");
        std::ofstream result(argv[9]);
        require(result.is_open(), "cannot open result file");
        auto context = std::make_unique<VerilatedContext>();
        context->randSeed(seed);
        context->randReset(2);
        auto dut = std::make_unique<VChapter8>(context.get());
        CoverageSnapshot coverage{context.get()};
        unsigned pins = 0;
        uint64_t time = initialTime;
        auto drive = [&]() {
            dut->interrupts_MExtInt = (pins & 1) != 0;
            dut->interrupts_SExtInt = (pins & 2) != 0;
            dut->interrupts_MTimerInt = (pins & 4) != 0;
            dut->interrupts_MSwInt = (pins & 8) != 0;
            dut->interrupts_TIME_REGW = time;
        };
        auto tick = [&]() {
            dut->clk = 1; dut->eval(); context->timeInc(1);
            dut->clk = 0; dut->eval(); context->timeInc(1);
        };
        dut->clk = 0; dut->reset = 1; drive(); dut->eval();
        result << "{\"seed\":" << seed << ",\"runs\":[";
        std::string failure;
        for (unsigned repeat = 0; repeat < 2 && failure.empty(); ++repeat) {
            pins = 0; time = initialTime; dut->reset = 1; drive(); dut->eval();
            for (unsigned n = 0; n < 3 + seed % 3; ++n) tick();
            dut->reset = 0; dut->eval();
            unsigned cycle = 0, index = 0;
            int raiseAt = -1, doneAt = -1;
            std::vector<std::pair<unsigned, Event>> actual;
            for (; cycle < limit && failure.empty() && (doneAt < 0 || int(cycle) < doneAt + 9); ++cycle) {
                // Falling edge has completed. Inputs now settle before checking requests.
                if (int(cycle) == raiseAt) { pins = requestedPins; time = raisedTime; }
                drive(); dut->eval();
                require(bool(dut->io_MemWrite) == bool(dut->io_ByteMask), "write/mask inconsistency");
                if (dut->io_MemWrite) {
                    Event e{dut->io_IEUAdr, dut->io_WriteData, dut->io_ByteMask};
                    actual.emplace_back(cycle, e);
                    if (index >= expected.size()) failure = "extra store";
                    else if (e != expected[index]) failure = "signature mismatch at event " + std::to_string(index);
                    ++index;
                    if (e[0] == 600 && delayed) raiseAt = cycle + 9 + seed % 3;
                    if (e[0] == 604) { pins = 0; time = initialTime; }
                    if (e[0] == 512 && e[1] == 0x5a && e[2] == 15) doneAt = cycle;
                }
                tick();
            }
            if (failure.empty() && doneAt < 0) failure = "timeout";
            if (failure.empty() && doneAt >= 0 && int(cycle) < doneAt + 9)
                failure = "timeout: incomplete observation window";
            if (failure.empty() && index != expected.size()) failure = "missing store";
            if (repeat) result << ',';
            result << "{\"repeat\":" << repeat << ",\"cycles\":" << cycle
                   << ",\"pass\":" << (failure.empty() ? "true" : "false")
                   << ",\"failure\":\"" << failure << "\",\"actual\":[";
            for (size_t n = 0; n < actual.size(); ++n) {
                if (n) result << ',';
                writeEvent(result, actual[n].second, actual[n].first);
            }
            result << "]}";
        }
        dut->final();
        result << "],\"pass\":" << (failure.empty() ? "true" : "false")
               << ",\"failure\":\"" << failure << "\"}\n";
        if (!failure.empty()) {
            std::cerr << argv[1] << " seed=" << seed << ": " << failure << '\n';
            return 1;
        }
        std::cout << argv[1] << " seed=" << seed << " PASS (same-instance reset replay)\n";
        return 0;
    } catch (const std::exception& e) {
        std::cerr << e.what() << '\n'; return 1;
    }
}
