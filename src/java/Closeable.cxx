// SPDX-License-Identifier: BSD-2-Clause
// author: Max Kellermann <max.kellermann@gmail.com>

#include "Closeable.hxx"
#include "Class.hxx"
#include "String.hxx"

#include <android/log.h>

namespace Java {

static jmethodID close_method;

void
InitialiseCloseable(JNIEnv *env) noexcept
{
	Java::Class cls(env, "java/io/Closeable");
	close_method = env->GetMethodID(cls, "close", "()V");
}

void
CloseCloseable(JNIEnv *env, jobject obj) noexcept
{
	env->CallVoidMethod(obj, close_method);
	DiscardException(env);
}

GlobalCloseable::~GlobalCloseable() noexcept
{
	const jobject o = Get();
	__android_log_print(ANDROID_LOG_WARN, "XCSDBG",
			    "~GlobalCloseable o=%p", o);
	if (o != nullptr)
		CloseCloseable(Java::GetEnv(), o);
}

LocalCloseable::~LocalCloseable() noexcept
{
	const jobject o = Get();
	__android_log_print(ANDROID_LOG_WARN, "XCSDBG",
			    "~LocalCloseable o=%p -> calling close()", o);
	if (o != nullptr)
		CloseCloseable(GetEnv(), o);
}

} // namespace Java
