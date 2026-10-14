#pragma once
#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>

// Consumes actual CPU store events. The readback stream is never synthesized
// from the shadow memory: only software LW -> protocol SW supplies its values.
class SignatureProtocol {
public:
    static constexpr uint32_t doneAddress = 0x8021ff00;
    static constexpr uint32_t indexAddress = 0x8021ff20;
    static constexpr uint32_t dataAddress = 0x8021ff24;
    static constexpr uint32_t lengthAddress = 0x8021ff28;
    static constexpr uint32_t memoryBase = 0x80200000;
    static constexpr uint32_t capacity = 128 * 1024;

    SignatureProtocol(uint32_t base, const std::vector<uint32_t>& reference)
        : base_(base), expected_(reference), ram_(capacity), written_(capacity) {
        check(!expected_.empty() && !(base & 3) && base >= memoryBase &&
              uint64_t(base) + expected_.size() * 4 <= doneAddress,
              "Signature outside platform signature area");
    }

    void observe(uint32_t address, uint32_t mask, uint32_t funct3, uint32_t value) {
        check(!done_, "Late store after completion");
        check(address >= memoryBase && address < memoryBase + capacity,
              "Store outside platform DTIM range");
        check(funct3 <= 2 && mask && mask <= 15, "Unsupported actual store size");
        const unsigned size = 1u << funct3;
        const uint32_t offset = address - memoryBase;
        check((address & (size - 1)) == 0 &&
              mask == (((1u << size) - 1) << (address & 3)), "Invalid store byte mask");
        const uint32_t aligned = offset & ~3u;
        for (unsigned lane = 0; lane < 4; ++lane) {
            if ((mask >> lane) & 1) {
                ram_[aligned + lane] = value >> (8 * (lane % size));
                written_[aligned + lane] = 1;
            }
        }
        const uint32_t wordAddress = address & ~3u;
        if (wordAddress == lengthAddress || wordAddress == indexAddress ||
            wordAddress == dataAddress || wordAddress == doneAddress)
            check(mask == 15 && funct3 == 2 && address == wordAddress,
                  "Protocol output must be an aligned word store");
        if (address == lengthAddress) {
            check(!lengthSeen_, "Duplicate signature length");
            check(value == expected_.size(), "Signature length differs");
            lengthSeen_ = true;
        } else if (address == indexAddress) {
            check(lengthSeen_, "Missing signature length");
            check(!indexPending_, "Duplicate signature index before data");
            check(value == readback_.size() && value < expected_.size(),
                  "Signature index out of order or duplicate");
            indexPending_ = true;
        } else if (address == dataAddress) {
            check(lengthSeen_, "Missing signature length");
            check(indexPending_, "Signature data without index");
            const unsigned index = readback_.size();
            checkWord(index, value);
            readback_.push_back(value);
            indexPending_ = false;
        } else if (address == doneAddress) {
            check(value == 1, "Software reported failure status");
            check(lengthSeen_, "Missing signature length");
            check(!indexPending_ && readback_.size() == expected_.size(),
                  "Missing signature readback data");
            for (unsigned i = 0; i < expected_.size(); ++i) checkWord(i, readback_[i]);
            done_ = true;
        }
    }

    void finish() const { check(done_, "Cycle limit reached before completion"); }
    bool done() const { return done_; }
    const std::vector<uint32_t>& readback() const { return readback_; }
    std::vector<uint32_t> reconstructed() const {
        std::vector<uint32_t> result;
        for (unsigned i = 0; i < expected_.size(); ++i) result.push_back(word(i, false));
        return result;
    }

private:
    static void check(bool condition, const std::string& message) {
        if (!condition) throw std::runtime_error(message);
    }
    uint32_t word(unsigned index, bool requireWritten) const {
        uint32_t value = 0;
        for (unsigned lane = 0; lane < 4; ++lane) {
            const uint32_t address = base_ - memoryBase + 4 * index + lane;
            if (requireWritten) check(written_[address], "Signature contains unwritten byte");
            value |= uint32_t(ram_[address]) << (8 * lane);
        }
        return value;
    }
    void checkWord(unsigned index, uint32_t cpu) const {
        const uint32_t stores = word(index, true);
        check(stores == expected_[index], "Signature word " + std::to_string(index) +
              " differs: actual=" + std::to_string(stores) +
              " expected=" + std::to_string(expected_[index]));
        check(cpu == stores, "CPU readback word " + std::to_string(index) +
              " differs: cpu=" + std::to_string(cpu) + " stores=" + std::to_string(stores));
    }
    uint32_t base_;
    std::vector<uint32_t> expected_, readback_;
    std::vector<uint8_t> ram_, written_;
    bool lengthSeen_ = false, indexPending_ = false, done_ = false;
};
