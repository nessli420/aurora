#pragma once
#include <jni.h>
#include <functional>
#include <future>
#include <string>
#include <string_view>
#include <type_traits>
#include <vector>

namespace aurora {

std::wstring wide(JNIEnv* env, jstring value);
jstring javaString(JNIEnv* env, std::wstring_view value);
std::vector<unsigned char> bytes(JNIEnv* env, jbyteArray value);
void clearException(JNIEnv* env);

class Worker {
public:
    using Task = std::function<void(JNIEnv*)>;

    static void post(Task task);
    static bool current();

    template <class F>
    static auto call(F&& function) {
        using Result = std::invoke_result_t<F&, JNIEnv*>;
        if (current()) return function(env());
        std::promise<Result> promise;
        auto future = promise.get_future();
        post([&](JNIEnv* workerEnv) {
            try {
                if constexpr (std::is_void_v<Result>) {
                    function(workerEnv);
                    promise.set_value();
                } else {
                    promise.set_value(function(workerEnv));
                }
            } catch (...) {
                promise.set_exception(std::current_exception());
            }
        });
        return future.get();
    }

private:
    static JNIEnv* env();
};

}
