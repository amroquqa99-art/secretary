#pragma once
#include "llama.h"
#include <atomic>
#include <chrono>
#include <string>
#include <vector>

namespace secretary {
class Session {
public:
    explicit Session(const std::string& path,bool embedding_mode=false);
    ~Session();
    Session(const Session&)=delete;
    Session& operator=(const Session&)=delete;
    void cancel() { cancelled.store(true); }
    std::string generate(const std::string& system,const std::string& user,const std::string& grammar,int maximum);
    std::vector<float> embed(const std::string& text);
private:
    llama_model* model=nullptr;
    llama_context* context=nullptr;
    bool embedding_mode=false;
    std::atomic<bool> cancelled{false};
    std::atomic<bool> generating{false};
    std::chrono::steady_clock::time_point deadline;
    bool stopped() const;
    void check() const;
    void cleanup();
    static bool abort(void* data);
    static bool progress(float value,void* data);
};
}
