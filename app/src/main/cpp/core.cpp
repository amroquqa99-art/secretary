#include "core.h"
#include <algorithm>
#include <cmath>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <vector>

namespace secretary {
namespace {std::once_flag initialized;}
bool Session::stopped() const {return cancelled.load() || std::chrono::steady_clock::now()>=deadline;}
void Session::check() const {if(stopped())throw std::runtime_error("Cancelled or timed out");}
bool Session::abort(void* data){return static_cast<Session*>(data)->stopped();}
bool Session::progress(float,void* data){return !static_cast<Session*>(data)->stopped();}
Session::Session(const std::string& path,bool embedding_mode):embedding_mode(embedding_mode),deadline(std::chrono::steady_clock::now()+std::chrono::seconds(120)) {
    std::call_once(initialized,[]{llama_backend_init();});
    try {
        auto parameters=llama_model_default_params();
        parameters.n_gpu_layers=0;
        parameters.load_mode=LLAMA_LOAD_MODE_MMAP;
        parameters.progress_callback=progress;
        parameters.progress_callback_user_data=this;
        model=llama_model_load_from_file(path.c_str(),parameters);
        if(!model)throw std::runtime_error("Cannot load this GGUF model");
        check();
        auto settings=llama_context_default_params();
        settings.n_ctx=embedding_mode?1024:2048;
        settings.n_batch=embedding_mode?1024:128;
        settings.n_ubatch=embedding_mode?1024:128;
        settings.n_threads=2;settings.n_threads_batch=2;
        settings.abort_callback=abort;settings.abort_callback_data=this;
        if(embedding_mode) {
            settings.embeddings=true;
            settings.pooling_type=LLAMA_POOLING_TYPE_LAST;
        }
        context=llama_init_from_model(model,settings);
        if(!context)throw std::runtime_error("Cannot allocate model context");
        check();
    } catch(...) {cleanup();throw;}
}
Session::~Session(){cleanup();}
void Session::cleanup(){if(context){llama_free(context);context=nullptr;}if(model){llama_model_free(model);model=nullptr;}}
std::string Session::generate(const std::string& system,const std::string& user,const std::string& grammar,int maximum) {
    if(embedding_mode)throw std::runtime_error("Embedding session cannot generate text");
    bool expected=false;
    if(!generating.compare_exchange_strong(expected,true))throw std::runtime_error("Generation already running");
    struct Guard {std::atomic<bool>& flag;~Guard(){flag.store(false);}} guard{generating};
    if(maximum<1 || maximum>256 || system.size()>8192 || user.size()>16384 || grammar.size()>8192 || system.find('\0')!=std::string::npos || user.find('\0')!=std::string::npos)throw std::invalid_argument("Invalid inference limits");
    check();
    llama_memory_clear(llama_get_memory(context),true);
    const llama_chat_message messages[]={{"system",system.c_str()},{"user",user.c_str()}};
    const char* format=llama_model_chat_template(model,nullptr);
    std::vector<char> formatted((system.size()+user.size())*2+1024);
    int size=llama_chat_apply_template(format,messages,2,true,formatted.data(),formatted.size());
    if(size<0 || size>32768)throw std::runtime_error("Unsupported model chat template");
    if(size>static_cast<int>(formatted.size())) {
        formatted.resize(size+1);size=llama_chat_apply_template(format,messages,2,true,formatted.data(),formatted.size());
        if(size<0 || size>=static_cast<int>(formatted.size()))throw std::runtime_error("Invalid chat template");
    }
    const auto* vocab=llama_model_get_vocab(model);
    int count=llama_tokenize(vocab,formatted.data(),size,nullptr,0,true,true);
    if(count>=0 || -count+maximum>2048)throw std::runtime_error("Context is too long; shorten the request");
    std::vector<llama_token> tokens(-count);
    count=llama_tokenize(vocab,formatted.data(),size,tokens.data(),tokens.size(),true,true);
    if(count<=0)throw std::runtime_error("Tokenization failed");
    for(int offset=0;offset<count;offset+=128) {
        check();int amount=std::min(128,count-offset);
        if(llama_decode(context,llama_batch_get_one(tokens.data()+offset,amount))!=0){check();throw std::runtime_error("Model prefill failed");}
    }
    using Sampler=std::unique_ptr<llama_sampler,decltype(&llama_sampler_free)>;
    Sampler sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()),llama_sampler_free);
    if(!sampler)throw std::runtime_error("Cannot allocate sampler");
    if(!grammar.empty()) {
        auto* constraint=llama_sampler_init_grammar(vocab,grammar.c_str(),"root");
        if(!constraint)throw std::runtime_error("Invalid action grammar");
        llama_sampler_chain_add(sampler.get(),constraint);
    }
    llama_sampler_chain_add(sampler.get(),llama_sampler_init_greedy());
    std::string output;
    for(int index=0;index<maximum;index++) {
        check();llama_token next=llama_sampler_sample(sampler.get(),context,-1);
        if(llama_vocab_is_eog(vocab,next))break;
        std::vector<char> piece(256);int n=llama_token_to_piece(vocab,next,piece.data(),piece.size(),0,false);
        if(n<0){if(-n>16384)throw std::runtime_error("Invalid token size");piece.resize(-n);n=llama_token_to_piece(vocab,next,piece.data(),piece.size(),0,false);}
        if(n<0 || output.size()+n>32768)throw std::runtime_error("Model output is too long");
        output.append(piece.data(),n);
        if(llama_decode(context,llama_batch_get_one(&next,1))!=0){check();throw std::runtime_error("Model decoding failed");}
    }
    check();return output;
}
std::vector<float> Session::embed(const std::string& text) {
    if(!embedding_mode)throw std::runtime_error("Text generation session cannot create embeddings");
    bool expected=false;
    if(!generating.compare_exchange_strong(expected,true))throw std::runtime_error("Embedding already running");
    struct Guard {std::atomic<bool>& flag;~Guard(){flag.store(false);}} guard{generating};
    if(text.empty() || text.size()>8192 || text.find('\0')!=std::string::npos)throw std::invalid_argument("Invalid embedding input");
    check();
    const auto* vocab=llama_model_get_vocab(model);
    int count=llama_tokenize(vocab,text.data(),text.size(),nullptr,0,true,true);
    if(count>=0 || -count>1024)throw std::runtime_error("Embedding input is too long");
    std::vector<llama_token> tokens(-count);
    count=llama_tokenize(vocab,text.data(),text.size(),tokens.data(),tokens.size(),true,true);
    if(count<=0)throw std::runtime_error("Embedding tokenization failed");

    llama_memory_clear(llama_get_memory(context),true);
    llama_batch batch=llama_batch_init(count,0,1);
    if(!batch.token || !batch.pos || !batch.n_seq_id || !batch.seq_id || !batch.logits) {
        llama_batch_free(batch);
        throw std::runtime_error("Cannot allocate embedding batch");
    }
    batch.n_tokens=count;
    for(int i=0;i<count;i++) {
        batch.token[i]=tokens[i];
        batch.pos[i]=i;
        batch.n_seq_id[i]=1;
        batch.seq_id[i][0]=0;
        batch.logits[i]=1;
    }
    const int rc=llama_decode(context,batch);
    llama_batch_free(batch);
    if(rc!=0){check();throw std::runtime_error("Embedding decode failed");}
    check();
    float* source=llama_get_embeddings_seq(context,0);
    if(!source)throw std::runtime_error("Model does not expose pooled embeddings");
    const int dim=llama_model_n_embd_out(model);
    if(dim<1 || dim>8192)throw std::runtime_error("Invalid embedding dimension");
    std::vector<float> result(source,source+dim);
    double sum=0.0;
    for(float value:result)sum+=static_cast<double>(value)*value;
    const double norm=std::sqrt(sum);
    if(!std::isfinite(norm) || norm<=0.0)throw std::runtime_error("Invalid embedding vector");
    for(float& value:result)value=static_cast<float>(value/norm);
    return result;
}
}
