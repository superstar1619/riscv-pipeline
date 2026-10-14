#include "SignatureProtocol.h"
#include <functional>
#include <iostream>
#include <stdexcept>
#include <string>

static constexpr uint32_t base = 0x80210000;
static void rejects(const std::string& reason, const std::function<void()>& run) {
    try { run(); } catch (const std::runtime_error& e) {
        if (std::string(e.what()).find(reason) != std::string::npos) return;
        throw std::runtime_error("Wrong rejection, wanted " + reason + ", got " + e.what());
    }
    throw std::runtime_error("Did not reject " + reason);
}
static void store(SignatureProtocol& p, uint32_t address, uint32_t value) {
    p.observe(address, 15, 2, value);
}
int main() {
    try {
        rejects("Missing signature length", [] {
            SignatureProtocol p(base, {0x12345678});
            store(p, base, 0x12345678);
            store(p, 0x8021ff00, 1);
        });
        auto initialized = [] {
            SignatureProtocol p(base, {0x12345678, 0xabcdef01});
            // Byte and halfword writes exercise the actual subword reconstruction.
            p.observe(base, 1, 0, 0x78);
            p.observe(base + 1, 2, 0, 0x56);
            p.observe(base + 2, 12, 1, 0x1234);
            store(p, base + 4, 0xabcdef01);
            return p;
        };
        auto read = [](SignatureProtocol& p) {
            store(p, 0x8021ff28, 2);
            store(p, 0x8021ff20, 0);
            store(p, 0x8021ff24, 0x12345678);
            store(p, 0x8021ff20, 1);
            store(p, 0x8021ff24, 0xabcdef01);
        };
        auto valid = initialized();
        read(valid); store(valid, 0x8021ff00, 1); valid.finish();
        if (valid.readback() != valid.reconstructed()) throw std::runtime_error("Readback mismatch");
        rejects("Late store", [&] { store(valid, base, 0); });
        rejects("Late store", [&] { store(valid, 0x8021ff00, 1); });
        rejects("Signature length differs", [&] { auto p = initialized(); store(p, 0x8021ff28, 1); });
        rejects("Duplicate signature length", [&] { auto p = initialized(); read(p); store(p, 0x8021ff28, 2); });
        rejects("Missing signature readback data", [&] { auto p = initialized(); store(p, 0x8021ff28, 2); store(p, 0x8021ff00, 1); });
        rejects("Missing signature readback data", [&] { auto p = initialized(); store(p, 0x8021ff28, 2); store(p, 0x8021ff20, 0); store(p, 0x8021ff00, 1); });
        rejects("Duplicate signature index", [&] { auto p = initialized(); store(p, 0x8021ff28, 2); store(p, 0x8021ff20, 0); store(p, 0x8021ff20, 0); });
        rejects("out of order or duplicate", [&] { auto p = initialized(); store(p, 0x8021ff28, 2); store(p, 0x8021ff20, 1); });
        rejects("out of order or duplicate", [&] { auto p = initialized(); read(p); store(p, 0x8021ff20, 1); });
        rejects("data without index", [&] { auto p = initialized(); store(p, 0x8021ff28, 2); store(p, 0x8021ff24, 0x12345678); });
        rejects("CPU readback word 0 differs", [&] { auto p = initialized(); store(p, 0x8021ff28, 2); store(p, 0x8021ff20, 0); store(p, 0x8021ff24, 0); });
        rejects("Signature word 0 differs", [&] { auto p = initialized(); store(p, base, 0); read(p); });
        rejects("Signature word 0 differs", [&] { auto p = initialized(); read(p); store(p, base, 0); store(p, 0x8021ff00, 1); });
        rejects("unwritten byte", [&] { SignatureProtocol p(base, {0x12345678, 0xabcdef01}); read(p); });
        rejects("Cycle limit reached", [&] { auto p = initialized(); read(p); p.finish(); });
        rejects("Software reported failure", [&] { auto p = initialized(); read(p); store(p, 0x8021ff00, 3); });
        rejects("aligned word store", [&] { auto p = initialized(); p.observe(0x8021ff29, 2, 0, 2); });
        rejects("Invalid store byte mask", [&] { auto p = initialized(); p.observe(base, 2, 0, 1); });
        std::cout << "PASS signature protocol\n";
    } catch (const std::exception& e) {
        std::cerr << e.what() << '\n'; return 1;
    }
}
