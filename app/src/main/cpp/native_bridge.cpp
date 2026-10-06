#include "core.h"
#include <jni.h>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <unordered_map>

namespace {
std::mutex lock;
std::unordered_map<jlong,std::shared_ptr<secretary::Session>> sessions;
jlong sequence=0;
std::string bytes(JNIEnv* env,jbyteArray value) {
    if(!value)throw std::invalid_argument("Missing input");
    auto count=env->GetArrayLength(value);if(count>32768)throw std::invalid_argument("Input is too long");
    std::string result(count,'\0');env->GetByteArrayRegion(value,0,count,reinterpret_cast<jbyte*>(result.data()));
    if(env->ExceptionCheck())throw std::runtime_error("Cannot read input");
    return result;
}
std::shared_ptr<secretary::Session> get(jlong handle) {
    std::lock_guard<std::mutex> guard(lock);auto found=sessions.find(handle);
    if(found==sessions.end())throw std::invalid_argument("Model session is closed");
    return found->second;
}
void fail(JNIEnv* env,const std::exception& error){if(!env->ExceptionCheck())env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),error.what());}
}
extern "C" JNIEXPORT jlong JNICALL Java_com_alsekretary_app_localmodel_NativeBridge_open(JNIEnv* env,jobject,jstring path) {
    try {
        if(!path)throw std::invalid_argument("Missing model path");
        const char* encoded=env->GetStringUTFChars(path,nullptr);
        if(!encoded)throw std::runtime_error("Cannot read model path");
        std::string filename(encoded);env->ReleaseStringUTFChars(path,encoded);
        auto session=std::make_shared<secretary::Session>(filename);
        std::lock_guard<std::mutex> guard(lock);jlong id=++sequence;sessions.emplace(id,std::move(session));return id;
    } catch(const std::exception& error){fail(env,error);return 0;}
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_alsekretary_app_localmodel_NativeBridge_generate(JNIEnv* env,jobject,jlong id,jbyteArray system,jbyteArray user,jbyteArray grammar,jint maximum) {
    try {
        auto result=get(id)->generate(bytes(env,system),bytes(env,user),bytes(env,grammar),maximum);
        auto output=env->NewByteArray(result.size());if(output)env->SetByteArrayRegion(output,0,result.size(),reinterpret_cast<const jbyte*>(result.data()));return output;
    } catch(const std::exception& error){fail(env,error);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_com_alsekretary_app_localmodel_NativeBridge_cancel(JNIEnv*,jobject,jlong id) {try{get(id)->cancel();}catch(const std::exception&){} }
extern "C" JNIEXPORT void JNICALL Java_com_alsekretary_app_localmodel_NativeBridge_close(JNIEnv*,jobject,jlong id) {std::lock_guard<std::mutex> guard(lock);sessions.erase(id);}
